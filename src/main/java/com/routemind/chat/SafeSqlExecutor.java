package com.routemind.chat;

import com.routemind.llm.LlmChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The last-resort path: let the model write ONE read-only query, but only over a slice
 * that has already been filtered, and only when no existing API could answer.
 *
 * This is the dangerous capability in the product, so it is fenced on five sides:
 *
 *   1. READ ONLY      a single leading SELECT/WITH. Anything that could write, alter or
 *                     escalate is rejected outright, and the connection is not trusted to
 *                     enforce that for us.
 *   2. ALLOW-LISTED   only the analytic tables. No system catalogues, no credentials
 *                     tables, no pg_* introspection.
 *   3. PRE-FILTERED   the date range and business unit are injected by US as bound
 *                     parameters, not written by the model. It cannot widen its own scope
 *                     to the whole database.
 *   4. BOUNDED        a hard LIMIT and a query timeout, so a careless join cannot pin the
 *                     connection pool.
 *   5. TWO ATTEMPTS   {@link QueryPlanner#MAX_ATTEMPTS}. If the second query is still
 *                     invalid we give up and the caller answers from the API facts alone.
 *
 * A rejected query is not an error the user sees — the chatbot falls back to the
 * deterministic answer, exactly as it does when the model is unavailable.
 */
@Component
public class SafeSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SafeSqlExecutor.class);

    /** Only these tables are readable. Everything else — including pg_* — is refused. */
    private static final List<String> ALLOWED_TABLES = List.of(
            "trips", "trip_employees", "billing", "feedback", "alerts", "vendor_fleet");

    /** Anything that writes, changes structure, or chains a second statement. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|"
            + "vacuum|analyze|reindex|call|do|execute|prepare|listen|notify|set|reset)\\b",
            Pattern.CASE_INSENSITIVE);

    /** pg_sleep and friends: cheap ways to hold a connection open. */
    private static final Pattern FORBIDDEN_FN = Pattern.compile(
            "\\b(pg_sleep|pg_read_file|pg_ls_dir|lo_import|lo_export|dblink|current_setting)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_ROWS = 50;
    private static final int TIMEOUT_SECONDS = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final LlmChat llm;

    public SafeSqlExecutor(NamedParameterJdbcTemplate jdbc, LlmChat llm) {
        this.jdbc = jdbc;
        this.llm = llm;
    }

    public record SqlResult(boolean ok, String sql, List<Map<String, Object>> rows,
                            String note, int attempts) {}

    private static final String SYSTEM = """
            Write ONE read-only PostgreSQL query answering the question.

            HARD RULES
            - SELECT (or WITH ... SELECT) only. Never write, alter or chain statements.
            - Read only these tables: trips, trip_employees, billing, feedback, alerts,
              vendor_fleet.
            - The caller ALREADY filters the period and business unit. You MUST include the
              placeholders :from, :to and the business-unit guard exactly as shown:
                  WHERE t.trip_date BETWEEN :from AND :to
                    AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
            - Aggregate. Return at most 20 rows, ordered so the most important is first.
            - No comments, no markdown fences, no trailing semicolon. SQL only.

            Useful columns on trips: trip_date, business_unit, vendor, office, product_type,
            shift_type, shift_band, trip_direction, delay_minutes, delay_reason.
            A trip is late when delay_minutes > 10.
            """;

    /**
     * Ask the model for a query, validate it, run it. Retries once — and only once — with
     * the rejection reason fed back, then gives up.
     */
    public SqlResult run(String question, LocalDate from, LocalDate to, String businessUnit) {
        String feedback = "";
        for (int attempt = 1; attempt <= QueryPlanner.MAX_ATTEMPTS; attempt++) {
            Optional<String> draft = llm.ask(SYSTEM,
                    "Question: " + question + feedback, 400,
                    "sql-fallback attempt " + attempt);
            if (draft.isEmpty()) {
                return new SqlResult(false, null, List.of(),
                        "no model available for the SQL fallback", attempt);
            }

            String sql = strip(draft.get());
            String rejection = validate(sql);
            if (rejection != null) {
                log.info("SQL fallback attempt {} rejected: {}", attempt, rejection);
                feedback = "\nYour previous query was rejected because: " + rejection
                         + "\nFix it and reply with SQL only.";
                continue;
            }

            try {
                MapSqlParameterSource p = new MapSqlParameterSource()
                        .addValue("from", from).addValue("to", to)
                        .addValue("bu", businessUnit);
                jdbc.getJdbcTemplate().setQueryTimeout(TIMEOUT_SECONDS);
                List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
                if (rows.size() > MAX_ROWS) rows = rows.subList(0, MAX_ROWS);
                return new SqlResult(true, sql, rows,
                        "answered by a bounded read over the filtered slice", attempt);
            } catch (Exception e) {
                log.info("SQL fallback attempt {} failed to execute: {}", attempt, e.getMessage());
                feedback = "\nYour previous query failed with: " + e.getMessage()
                         + "\nFix it and reply with SQL only.";
            }
        }
        return new SqlResult(false, null, List.of(),
                "could not build a valid query in " + QueryPlanner.MAX_ATTEMPTS + " attempts",
                QueryPlanner.MAX_ATTEMPTS);
    }

    /** Remove markdown fences and stray semicolons the model tends to add. */
    static String strip(String s) {
        String out = s.trim();
        if (out.startsWith("```")) {
            out = out.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        while (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
        return out;
    }

    /** @return null when the query is acceptable, otherwise why it was refused. */
    static String validate(String sql) {
        if (sql == null || sql.isBlank()) return "empty query";

        String lower = sql.toLowerCase(Locale.ROOT);

        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return "must start with SELECT or WITH";
        }
        // One statement only. A semicolon has already been stripped from the end, so any
        // that remains is separating a second statement.
        if (sql.contains(";")) return "only one statement is allowed";
        if (FORBIDDEN.matcher(lower).find()) return "contains a non-read-only keyword";
        if (FORBIDDEN_FN.matcher(lower).find()) return "contains a forbidden function";
        if (lower.contains("pg_") || lower.contains("information_schema")) {
            return "system catalogues are not readable";
        }
        // The scope guards are injected by us; a query without them is unbounded.
        if (!lower.contains(":from") || !lower.contains(":to")) {
            return "must filter the period with :from and :to";
        }
        if (!lower.contains(":bu")) {
            return "must include the business-unit guard on :bu";
        }
        // Every table it reads must be on the allow-list.
        // (?<![:\w]) so the bind parameter ":from" is not read as the FROM clause — without
        // it, ":from AND :to" parses as a table named "and" and every valid query is refused.
        var m = Pattern.compile("(?<![:\\w])\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
                Pattern.CASE_INSENSITIVE).matcher(lower);
        while (m.find()) {
            String table = m.group(1);
            // CTE names are fine — they resolve to allow-listed tables underneath.
            boolean isCte = Pattern.compile("\\b" + Pattern.quote(table) + "\\s+as\\s*\\(",
                    Pattern.CASE_INSENSITIVE).matcher(lower).find();
            if (!isCte && !ALLOWED_TABLES.contains(table)) {
                return "table '" + table + "' is not readable";
            }
        }
        return null;
    }
}
