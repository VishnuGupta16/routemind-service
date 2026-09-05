package com.routemind.report;

import com.routemind.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one alert end to end: resolve its definition, generate, store, decide whether to
 * send, dispatch, record.
 *
 * The order matters. The report is STORED before any send is attempted, so a mail server
 * being down loses a delivery rather than a month's analysis.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final Map<String, ReportGenerator> generators = new LinkedHashMap<>();
    private final ReportRepository reports;
    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;

    public ReportService(List<ReportGenerator> all, ReportRepository reports,
                         NotificationService notifications, NamedParameterJdbcTemplate jdbc) {
        for (ReportGenerator g : all) generators.put(g.key(), g);
        this.reports = reports;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    public record Outcome(long reportId, String alertCode, String status, String headline,
                          double severity, boolean actionable, Map<String, Integer> delivery) {}

    /** Every alert the database knows about, whether or not a generator exists for it. */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> out = jdbc.queryForList("""
                SELECT a.id, a.code, a.name, a.description, a.generator_key,
                       a.lookback_days, a.compare_days, a.send_only_if_actionable, a.active,
                       p.code AS persona_code, p.name AS persona_name
                FROM alert_definition a JOIN persona p ON p.id = a.persona_id
                ORDER BY a.code
                """, new MapSqlParameterSource());
        // Surfaced rather than hidden: a row whose generator_key matches no @Component is a
        // configuration error that would otherwise only appear as a silent no-op at 08:00.
        for (Map<String, Object> m : out) {
            m.put("implemented", generators.containsKey(String.valueOf(m.get("generator_key"))));
        }
        return out;
    }

    /**
     * Run an alert for a period.
     *
     * @param asOf   the period END; the alert's own lookback_days decides how far back it
     *               reaches, so a monthly and a daily alert share this one entry point.
     * @param force  send even when the alert is configured to stay quiet unless actionable
     */
    public Outcome run(String alertCode, LocalDate asOf, String businessUnit, boolean force) {
        Map<String, Object> def = definition(alertCode);
        String generatorKey = String.valueOf(def.get("generator_key"));
        ReportGenerator generator = generators.get(generatorKey);
        if (generator == null) {
            throw new IllegalStateException(
                    "alert '" + alertCode + "' names generator '" + generatorKey
                            + "' but no ReportGenerator @Component has that key");
        }

        int lookback = ((Number) def.get("lookback_days")).intValue();
        int compare = ((Number) def.get("compare_days")).intValue();
        boolean onlyIfActionable = Boolean.TRUE.equals(def.get("send_only_if_actionable"));

        GeneratedReport report = generator.generate(
                ReportGenerator.Request.endingAt(
                        asOf == null ? LocalDate.now() : asOf, lookback, compare, businessUnit));

        boolean shouldSend = force || report.actionable() || !onlyIfActionable;
        // Stored first, and stored either way — "we looked and there was nothing to say" is
        // evidence, and without it a quiet month is indistinguishable from a broken job.
        long id = reports.save(report, shouldSend ? "GENERATED" : "SUPPRESSED");

        Map<String, Integer> delivery = Map.of();
        String status = "SUPPRESSED";
        if (shouldSend) {
            delivery = notifications.dispatch(id, report);
            status = delivery.getOrDefault("SENT", 0) > 0 ? "SENT" : "GENERATED";
            reports.markStatus(id, status);
        }

        log.info("alert {} for {}..{} -> report {} [{}] severity {}",
                alertCode, report.periodStart(), report.periodEnd(), id, status,
                report.severityScore());

        return new Outcome(id, alertCode, status, report.headline(),
                report.severityScore(), report.actionable(), delivery);
    }

    /** Generate without storing or sending — the preview button in the admin UI. */
    public GeneratedReport preview(String alertCode, LocalDate asOf, String businessUnit) {
        Map<String, Object> def = definition(alertCode);
        ReportGenerator g = generators.get(String.valueOf(def.get("generator_key")));
        if (g == null) throw new IllegalStateException("no generator for " + alertCode);
        return g.generate(ReportGenerator.Request.endingAt(
                asOf == null ? LocalDate.now() : asOf,
                ((Number) def.get("lookback_days")).intValue(),
                ((Number) def.get("compare_days")).intValue(), businessUnit));
    }

    private Map<String, Object> definition(String alertCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM alert_definition WHERE code = :code AND active",
                new MapSqlParameterSource("code", alertCode));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown or inactive alert: " + alertCode);
        }
        return rows.get(0);
    }
}
