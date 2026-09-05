package com.routemind.api;

import com.routemind.report.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The in-app alert inbox: what the UI shows, and the button that fills it.
 *
 * Alerts are delivered into the app rather than to an inbox, so the whole
 * sense → reason → notify loop is demonstrable with no mail server configured. The trigger
 * endpoint is the same {@link ReportService#run} the scheduler calls — the UI button and
 * the 07:00 cron take the identical path, which is what makes the demo honest.
 */
@RestController
@RequestMapping("/alerts")
@CrossOrigin
public class AlertInboxController {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportService reports;

    public AlertInboxController(NamedParameterJdbcTemplate jdbc, ReportService reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    /** The inbox. Newest first; `unreadOnly` drives the badge in the nav. */
    @GetMapping
    public List<Map<String, Object>> inbox(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT id, report_id, persona_code, business_unit, title, body, severity,
                       read_at, created_at
                FROM in_app_alert
                WHERE (CAST(:persona AS text) IS NULL OR persona_code = :persona)
                  AND (CAST(:bu AS text) IS NULL OR business_unit = :bu)
                  AND (NOT :unreadOnly OR read_at IS NULL)
                ORDER BY created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("persona", blank(persona))
                .addValue("bu", blank(businessUnit))
                .addValue("unreadOnly", unreadOnly)
                .addValue("limit", limit));
    }

    /** Unread counts per severity — everything the nav badge needs in one call. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<Map<String, Object>> bySeverity = jdbc.queryForList("""
                SELECT severity, count(*) AS n
                FROM in_app_alert WHERE read_at IS NULL
                GROUP BY severity
                """, new MapSqlParameterSource());
        Long unread = jdbc.queryForObject(
                "SELECT count(*) FROM in_app_alert WHERE read_at IS NULL",
                new MapSqlParameterSource(), Long.class);
        return Map.of("unread", unread == null ? 0 : unread, "bySeverity", bySeverity);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable long id) {
        int n = jdbc.update("UPDATE in_app_alert SET read_at = now() "
                        + "WHERE id = :id AND read_at IS NULL",
                new MapSqlParameterSource("id", id));
        return n == 0 ? ResponseEntity.notFound().build()
                      : ResponseEntity.ok(Map.of("read", n));
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        return Map.of("read", jdbc.update(
                "UPDATE in_app_alert SET read_at = now() WHERE read_at IS NULL",
                new MapSqlParameterSource()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> dismiss(@PathVariable long id) {
        return Map.of("deleted", jdbc.update("DELETE FROM in_app_alert WHERE id = :id",
                new MapSqlParameterSource("id", id)));
    }

    /**
     * Run an alert now and deliver it into the inbox — the "trigger from the UI" button.
     *
     * `force` sends even when the report would normally stay quiet (cooldown, or nothing
     * degrading). The demo needs that: a clean week is the common case, and a button that
     * usually does nothing visible is indistinguishable from a broken one.
     */
    @PostMapping("/trigger/{code}")
    public ReportService.Outcome trigger(
            @PathVariable String code,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            LocalDate asOf,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "true") boolean force) {
        return reports.run(code, asOf, blank(businessUnit), force);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
