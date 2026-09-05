package com.routemind.schedule;

import com.routemind.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs alerts on the schedules configured in the {@code alert_schedule} table.
 *
 * WHY NOT {@code @Scheduled(cron = "...")}
 * ----------------------------------------
 * A Spring cron annotation is fixed at compile time. The requirement is that an
 * administrator changes the cadence from the UI — daily, several times a day, weekly,
 * monthly — without a redeploy. So this ticks once a minute, reads the schedules from the
 * database, and runs whatever is due. One alert can hold several schedules (a daily brief
 * AND a Monday roll-up), which a single annotation could not express either.
 *
 * The tick is the only fixed thing in the system; everything about WHEN comes from the row.
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportService reports;
    private final boolean enabled;

    public AlertScheduler(NamedParameterJdbcTemplate jdbc, ReportService reports,
                          @Value("${routemind.schedule.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.enabled = enabled;
    }

    /**
     * Ticks every minute. Cheap: one indexed query against a table with a handful of rows.
     *
     * Kept unconditional as a bean with the guard INSIDE the method rather than a
     * {@code @ConditionalOnProperty} on the class — a conditional bean that something else
     * injects breaks startup the moment the property is turned off, which has bitten this
     * codebase once already.
     */
    @Scheduled(fixedDelayString = "${routemind.schedule.tick-ms:60000}")
    public void tick() {
        if (!enabled) return;
        for (Map<String, Object> s : due()) {
            runOne(s);
        }
    }

    /** Schedules that are active and whose next_run_at has passed (or was never set). */
    private List<Map<String, Object>> due() {
        return jdbc.queryForList("""
                SELECT s.id, s.cron_expression, s.timezone, s.frequency,
                       a.code AS alert_code
                FROM alert_schedule s
                JOIN alert_definition a ON a.id = s.alert_definition_id
                WHERE s.active AND a.active
                  AND (s.next_run_at IS NULL OR s.next_run_at <= now())
                """, new MapSqlParameterSource());
    }

    private void runOne(Map<String, Object> s) {
        long id = ((Number) s.get("id")).longValue();
        String alertCode = String.valueOf(s.get("alert_code"));
        String cron = String.valueOf(s.get("cron_expression"));
        ZoneId zone = zoneOf(String.valueOf(s.get("timezone")));

        // First sighting of a schedule only arms it. Running immediately would fire a
        // monthly report the moment someone saved the row, which is not what "monthly"
        // means to the person who configured it.
        if (s.get("next_run_at") == null && firstSighting(id)) {
            arm(id, cron, zone, "armed");
            return;
        }

        String status = "OK", note = null;
        try {
            var outcome = reports.run(alertCode, LocalDate.now(zone).minusDays(1), null, false);
            status = outcome.status();
            note = outcome.headline();
        } catch (Exception e) {
            status = "FAILED";
            note = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("scheduled alert {} failed: {}", alertCode, note);
        }
        // Always re-arm, including after a failure. A schedule that stops rescheduling
        // itself when something goes wrong goes quiet permanently, and nobody notices.
        arm(id, cron, zone, status, note);
    }

    private boolean firstSighting(long scheduleId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM alert_schedule WHERE id = :id AND last_run_at IS NULL",
                new MapSqlParameterSource("id", scheduleId), Integer.class);
        return n != null && n > 0;
    }

    private void arm(long id, String cron, ZoneId zone, String status) {
        arm(id, cron, zone, status, null);
    }

    private void arm(long id, String cron, ZoneId zone, String status, String note) {
        ZonedDateTime next;
        try {
            next = CronExpression.parse(cron).next(ZonedDateTime.now(zone));
        } catch (Exception e) {
            // A bad cron string must not kill the tick loop for every other schedule.
            log.warn("schedule {} has an invalid cron '{}' — pausing it", id, cron);
            jdbc.update("""
                    UPDATE alert_schedule
                       SET active = FALSE, last_run_status = 'FAILED',
                           last_run_note = :note, updated_at = now()
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", id)
                    .addValue("note", "invalid cron expression: " + cron));
            return;
        }

        jdbc.update("""
                UPDATE alert_schedule
                   SET last_run_at = CASE WHEN :armedOnly THEN last_run_at ELSE now() END,
                       last_run_status = :status,
                       last_run_note = :note,
                       next_run_at = :next,
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("armedOnly", "armed".equals(status))
                .addValue("status", "armed".equals(status) ? null : status)
                .addValue("note", note)
                .addValue("next", next == null ? null : next.toLocalDateTime()));
    }

    private static ZoneId zoneOf(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** Recompute next_run_at after the admin UI edits a cron — no restart needed. */
    public LocalDateTime rearm(long scheduleId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cron_expression, timezone FROM alert_schedule WHERE id = :id",
                new MapSqlParameterSource("id", scheduleId));
        ZoneId zone = zoneOf(String.valueOf(row.get("timezone")));
        ZonedDateTime next = CronExpression.parse(String.valueOf(row.get("cron_expression")))
                .next(ZonedDateTime.now(zone));
        jdbc.update("UPDATE alert_schedule SET next_run_at = :next, updated_at = now() "
                        + "WHERE id = :id",
                new MapSqlParameterSource("id", scheduleId)
                        .addValue("next", next == null ? null : next.toLocalDateTime()));
        return next == null ? null : next.toLocalDateTime();
    }
}
