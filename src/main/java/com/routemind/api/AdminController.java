package com.routemind.api;

import com.routemind.notify.NotificationService;
import com.routemind.report.GeneratedReport;
import com.routemind.report.ReportRepository;
import com.routemind.report.ReportService;
import com.routemind.schedule.AlertScheduler;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the admin UI needs: personas, alerts, schedules, recipients, subscriptions,
 * report history and the facts behind each report.
 *
 * All of it is configuration in the database, so the Streamlit demo and the Angular app are
 * both thin clients over the same endpoints — there is no behaviour here that only one of
 * them can reach.
 */
@RestController
@RequestMapping("/admin")
@CrossOrigin
public class AdminController {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportService reports;
    private final ReportRepository repository;
    private final NotificationService notifications;
    private final AlertScheduler scheduler;

    public AdminController(NamedParameterJdbcTemplate jdbc, ReportService reports,
                           ReportRepository repository, NotificationService notifications,
                           AlertScheduler scheduler) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.repository = repository;
        this.notifications = notifications;
        this.scheduler = scheduler;
    }

    // ------------------------------------------------------------- personas
    @GetMapping("/personas")
    public List<Map<String, Object>> personas() {
        return jdbc.queryForList("""
                SELECT id, code, name, description, decision_rights,
                       prompt_template IS NOT NULL AS has_prompt, prompt_version, active
                FROM persona ORDER BY id
                """, new MapSqlParameterSource());
    }

    /**
     * The per-persona prompt used in Phase 2. Editable now so the wording can be tuned
     * against real reports before an LLM is ever wired in — which is the part that
     * genuinely needs iterating.
     */
    @PutMapping("/personas/{code}/prompt")
    public Map<String, Object> updatePrompt(@PathVariable String code,
                                            @RequestBody Map<String, String> body) {
        int n = jdbc.update("""
                UPDATE persona SET prompt_template = :t, prompt_version = prompt_version + 1
                WHERE code = :code
                """, new MapSqlParameterSource("code", code)
                .addValue("t", body.get("promptTemplate")));
        return Map.of("updated", n);
    }

    // --------------------------------------------------------------- alerts
    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() { return reports.definitions(); }

    @GetMapping("/channels")
    public Map<String, Boolean> channels() { return notifications.channelStatus(); }

    // ------------------------------------------------------------ schedules
    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules() {
        return jdbc.queryForList("""
                SELECT s.id, a.code AS alert_code, a.name AS alert_name, s.frequency,
                       s.cron_expression, s.timezone, s.active,
                       s.last_run_at, s.last_run_status, s.last_run_note, s.next_run_at
                FROM alert_schedule s JOIN alert_definition a ON a.id = s.alert_definition_id
                ORDER BY a.code, s.id
                """, new MapSqlParameterSource());
    }

    /**
     * Change a cadence from the UI. The scheduler picks it up on its next tick — no
     * restart, which is the whole reason schedules live in the database.
     */
    @PutMapping("/schedules/{id}")
    public ResponseEntity<Map<String, Object>> updateSchedule(
            @PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            org.springframework.scheduling.support.CronExpression
                    .parse(String.valueOf(body.get("cronExpression")));
        } catch (Exception e) {
            // Reject at the boundary. A bad cron saved here would silently stop a report
            // from ever running again, and nobody notices a report that does not arrive.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid cron expression",
                    "detail", String.valueOf(e.getMessage()),
                    "hint", "Spring 6-field cron, e.g. '0 0 8 1 * *' for 08:00 on the 1st"));
        }
        jdbc.update("""
                UPDATE alert_schedule
                   SET frequency = COALESCE(:freq, frequency),
                       cron_expression = :cron,
                       timezone = COALESCE(:tz, timezone),
                       active = COALESCE(:active, active),
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("freq", body.get("frequency"))
                .addValue("cron", body.get("cronExpression"))
                .addValue("tz", body.get("timezone"))
                .addValue("active", body.get("active")));
        return ResponseEntity.ok(Map.of("id", id, "nextRunAt",
                String.valueOf(scheduler.rearm(id))));
    }

    // ----------------------------------------------------------- recipients
    @GetMapping("/recipients")
    public List<Map<String, Object>> recipients() {
        return jdbc.queryForList(
                "SELECT id, email, display_name, business_unit, active FROM recipient "
                        + "ORDER BY email", new MapSqlParameterSource());
    }

    @PostMapping("/recipients")
    public Map<String, Object> addRecipient(@RequestBody Map<String, String> body) {
        jdbc.update("""
                INSERT INTO recipient (email, display_name, business_unit)
                VALUES (:email, :name, :bu)
                ON CONFLICT (email) DO UPDATE
                   SET display_name = EXCLUDED.display_name,
                       business_unit = EXCLUDED.business_unit, active = TRUE
                """, new MapSqlParameterSource("email", body.get("email"))
                .addValue("name", body.get("displayName"))
                .addValue("bu", blankToNull(body.get("businessUnit"))));
        return Map.of("email", body.get("email"));
    }

    // -------------------------------------------------------- subscriptions
    /**
     * The many-to-many map: who receives which alert, as which persona, over what.
     */
    @GetMapping("/subscriptions")
    public List<Map<String, Object>> subscriptions() {
        return jdbc.queryForList("""
                SELECT sub.id, r.email, r.display_name, a.code AS alert_code,
                       a.name AS alert_name, p.code AS persona_code, p.name AS persona_name,
                       ch.kind AS channel_kind,
                       COALESCE(sub.business_unit, r.business_unit) AS business_unit,
                       sub.active
                FROM alert_subscription sub
                JOIN recipient r          ON r.id  = sub.recipient_id
                JOIN alert_definition a   ON a.id  = sub.alert_definition_id
                JOIN persona p            ON p.id  = sub.persona_id
                JOIN notification_channel ch ON ch.id = sub.channel_id
                ORDER BY r.email, a.code
                """, new MapSqlParameterSource());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Map<String, String> body) {
        int n = jdbc.update("""
                INSERT INTO alert_subscription (alert_definition_id, recipient_id,
                                                channel_id, persona_id, business_unit)
                SELECT a.id, r.id, ch.id, p.id, :bu
                FROM alert_definition a, recipient r, notification_channel ch, persona p
                WHERE a.code = :alertCode AND r.email = :email
                  AND ch.kind = :channelKind AND p.code = :personaCode
                ON CONFLICT (alert_definition_id, recipient_id, channel_id)
                DO UPDATE SET active = TRUE,
                              persona_id = EXCLUDED.persona_id,
                              business_unit = EXCLUDED.business_unit
                """, new MapSqlParameterSource()
                .addValue("alertCode", body.get("alertCode"))
                .addValue("email", body.get("email"))
                .addValue("channelKind", body.getOrDefault("channelKind", "EMAIL"))
                .addValue("personaCode", body.get("personaCode"))
                .addValue("bu", blankToNull(body.get("businessUnit"))));

        // The insert selects across four tables; if any lookup misses, it writes nothing
        // and would otherwise return a cheerful 200 having done nothing at all.
        return n == 0
                ? ResponseEntity.badRequest().body(Map.of("error",
                    "no match — check the alert code, email, channel and persona all exist"))
                : ResponseEntity.ok(Map.of("subscribed", n));
    }

    @DeleteMapping("/subscriptions/{id}")
    public Map<String, Object> unsubscribe(@PathVariable long id) {
        return Map.of("deactivated", jdbc.update(
                "UPDATE alert_subscription SET active = FALSE WHERE id = :id",
                new MapSqlParameterSource("id", id)));
    }

    // --------------------------------------------------------------- runs
    /** Run an alert now. `force` sends even when it would normally stay quiet. */
    @PostMapping("/alerts/{code}/run")
    public ReportService.Outcome run(
            @PathVariable String code,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "false") boolean force) {
        return reports.run(code, asOf, businessUnit, force);
    }

    /** Generate without storing or sending. */
    @GetMapping("/alerts/{code}/preview")
    public GeneratedReport preview(
            @PathVariable String code,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String businessUnit) {
        return reports.preview(code, asOf, businessUnit);
    }

    // ------------------------------------------------------------- reports
    @GetMapping("/reports")
    public List<Map<String, Object>> history(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "50") int limit) {
        return repository.history(persona, businessUnit, limit);
    }

    /**
     * The facts a report was written from, each with the query that produced it.
     *
     * This endpoint is the Phase 2 seam: an LLM asked "why did that drop?" is handed exactly
     * this and the persona's prompt, and nothing else — so it can only reason over numbers
     * that were actually measured.
     */
    @GetMapping("/reports/{id}/facts")
    public List<Map<String, Object>> facts(@PathVariable long id) {
        return repository.facts(id);
    }

    @GetMapping("/reports/{id}/deliveries")
    public List<Map<String, Object>> deliveries(@PathVariable long id) {
        return jdbc.queryForList("""
                SELECT channel_kind, target, status, error, sent_at
                FROM notification_log WHERE report_id = :id ORDER BY sent_at
                """, new MapSqlParameterSource("id", id));
    }

    /** Feedback on a report. Phase 2 uses it to tune each persona's prompt. */
    @PostMapping("/reports/{id}/feedback")
    public Map<String, Object> feedback(@PathVariable long id,
                                        @RequestBody Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO report_feedback (report_id, recipient_id, persona_id, rating,
                                             aspect, comment, prompt_version, generated_by)
                SELECT r.id,
                       (SELECT id FROM recipient WHERE email = :email),
                       r.persona_id, :rating, :aspect, :comment,
                       r.prompt_version, r.generated_by
                FROM generated_report r WHERE r.id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("email", body.get("email"))
                .addValue("rating", body.get("rating"))
                .addValue("aspect", body.get("aspect"))
                .addValue("comment", body.get("comment")));
        return Map.of("recorded", true);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
