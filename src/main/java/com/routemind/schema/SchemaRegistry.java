package com.routemind.schema;

import com.routemind.schema.SchemaChange.Profile;
import com.routemind.schema.SchemaChange.State;
import com.routemind.schema.SchemaChange.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns schema evolution: detect → propose → human decides → apply, backward compatibly.
 *
 * THE BACKWARD-COMPATIBILITY CONTRACT
 * -----------------------------------
 * 1. New columns are added NULLABLE with no default and no backfill. Historical rows
 *    therefore read NULL, which means "not collected then" — never 0, never ''.
 * 2. Nothing is ever dropped or retyped automatically; those require a human migration.
 * 3. Every adopted column records `availableFrom` — the first date it has data. Anything
 *    computing over it MUST scope to that date or it will average across a period where
 *    the column did not exist and silently report a wrong number.
 * 4. Existing queries are untouched: they never SELECT *, so a new column cannot change
 *    an existing metric's result.
 */
@Service
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    /** source -> table name in our schema */
    private static final Map<String, String> TABLES = Map.of(
            "trips", "trips",
            "trip_employees", "trip_employees",
            "billing", "billing",
            "alerts", "alerts",
            "feedback", "feedback");

    private final Map<String, SchemaChange> changes = new ConcurrentHashMap<>();
    private final SchemaAdvisor advisor;
    private final JdbcTemplate jdbc;

    public SchemaRegistry(SchemaAdvisor advisor, JdbcTemplate jdbc) {
        this.advisor = advisor;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- detection
    /**
     * Record a newly-seen column. Idempotent: re-running the same monthly drop does not
     * create duplicates or reset a decision that has already been made.
     */
    public SchemaChange recordNewColumn(String source, String column, String detectedIn,
                                        LocalDate availableFrom, Profile profile) {
        String id = key(Type.NEW_COLUMN, source, column);
        SchemaChange existing = changes.get(id);
        if (existing != null) return existing;      // already known / already decided

        String proposal = advisor.propose(source, column, profile);
        SchemaChange c = new SchemaChange(id, Type.NEW_COLUMN, source, column, detectedIn,
                availableFrom, profile, proposal, ddlFor(source, column, profile),
                State.PENDING, Instant.now(), null, null, null);
        changes.put(id, c);
        log.info("New column detected: {}.{} (from {})", source, column, availableFrom);
        return c;
    }

    public SchemaChange recordNewEnumValue(String source, String column, String detectedIn,
                                           List<String> values) {
        String id = key(Type.NEW_ENUM_VALUE, source, column + "=" + String.join(",", values));
        SchemaChange existing = changes.get(id);
        if (existing != null) return existing;

        Profile p = new Profile("enum", 100.0, values.size(), values);
        String proposal = """
                MEANING: New value(s) %s appeared in an existing category column.
                RECOMMEND: ADOPT
                WHY: Categories are stored as text, so new values ingest safely — but any
                     rule or breakdown that lists values explicitly must be updated.
                METRIC: may add a new breakdown category.""".formatted(values);

        SchemaChange c = new SchemaChange(id, Type.NEW_ENUM_VALUE, source, column, detectedIn,
                null, p, proposal,
                "-- no DDL required: category columns are TEXT and accept new values",
                State.PENDING, Instant.now(), null, null, null);
        changes.put(id, c);
        return c;
    }

    // -------------------------------------------------------------- decisions
    public Optional<SchemaChange> adopt(String id, String by, boolean applyNow) {
        SchemaChange c = changes.get(id);
        if (c == null) return Optional.empty();

        String note;
        if (c.type() == Type.NEW_COLUMN && applyNow) {
            try {
                jdbc.execute(c.migrationSql());
                note = "column added (nullable, no backfill); historical rows read NULL";
            } catch (Exception e) {
                note = "DDL failed — run it manually: " + e.getMessage();
                log.warn("Adopt {} failed: {}", id, e.getMessage());
            }
        } else {
            note = "adopted; no DDL required";
        }
        SchemaChange decided = c.decide(State.ADOPTED, by, note);
        changes.put(id, decided);
        return Optional.of(decided);
    }

    public Optional<SchemaChange> ignore(String id, String by, String reason) {
        return decide(id, State.IGNORED, by,
                reason == null || reason.isBlank()
                        ? "ignored — will not be ingested and will not warn again" : reason);
    }

    public Optional<SchemaChange> reject(String id, String by, String reason) {
        return decide(id, State.REJECTED, by,
                reason == null || reason.isBlank()
                        ? "rejected — future drops containing this will fail validation" : reason);
    }

    private Optional<SchemaChange> decide(String id, State s, String by, String note) {
        SchemaChange c = changes.get(id);
        if (c == null) return Optional.empty();
        SchemaChange d = c.decide(s, by, note);
        changes.put(id, d);
        return Optional.of(d);
    }

    // ---------------------------------------------------------------- queries
    public List<SchemaChange> all() {
        return changes.values().stream()
                .sorted(Comparator.comparing(SchemaChange::state)
                        .thenComparing(SchemaChange::source))
                .toList();
    }

    public List<SchemaChange> pending() {
        return changes.values().stream().filter(SchemaChange::isPending).toList();
    }

    /**
     * The date from which a column actually has data. Metrics using an adopted column
     * MUST NOT compute earlier than this, or they average over a period where the column
     * did not exist.
     */
    public Optional<LocalDate> availableFrom(String source, String column) {
        SchemaChange c = changes.get(key(Type.NEW_COLUMN, source, column));
        return c != null && c.state() == State.ADOPTED
                ? Optional.ofNullable(c.availableFrom()) : Optional.empty();
    }

    /** Columns the operator has decided about — the overlay validate.py reads. */
    public Map<String, Object> decisions() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SchemaChange c : changes.values()) {
            if (c.isPending()) continue;
            out.computeIfAbsent(c.source(), k -> new LinkedHashMap<String, Object>());
            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) out.get(c.source());
            src.put(c.column(), Map.of(
                    "state", c.state().name(),
                    "availableFrom", String.valueOf(c.availableFrom()),
                    "decidedBy", String.valueOf(c.decidedBy())));
        }
        return out;
    }

    public Map<String, Object> summary() {
        Map<String, Long> byState = new LinkedHashMap<>();
        for (State s : State.values()) {
            byState.put(s.name(), changes.values().stream().filter(c -> c.state() == s).count());
        }
        return Map.of("total", changes.size(), "byState", byState,
                "advisor", advisor.usingModel() ? "llm" : "heuristic");
    }

    // ------------------------------------------------------------------ DDL
    /**
     * Backward-compatible DDL: nullable, no default, no backfill, IF NOT EXISTS.
     * Existing rows keep reading NULL = "not collected then".
     */
    String ddlFor(String source, String column, Profile p) {
        String table = TABLES.getOrDefault(source, source);
        // The column name arrives from a CSV header — i.e. from outside. Reduce it to a
        // safe identifier rather than interpolating it: anything else is a DDL injection
        // waiting to happen. A leading digit is illegal unquoted in Postgres, so prefix it.
        String safeCol = column.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (safeCol.isBlank() || Character.isDigit(safeCol.charAt(0))) safeCol = "c_" + safeCol;
        String type = switch (p.inferredType() == null ? "" : p.inferredType().toUpperCase()) {
            case "INTEGER", "INT", "BIGINT" -> "BIGINT";
            case "NUMBER", "DECIMAL", "FLOAT", "REAL" -> "NUMERIC(14,3)";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "TIMESTAMP", "DATETIME" -> "TIMESTAMPTZ";
            case "DATE" -> "DATE";
            default -> "TEXT";
        };
        return "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + safeCol + " " + type;
    }

    private static String key(Type t, String source, String column) {
        return t.name() + ":" + source + ":" + column;
    }
}
