package com.routemind.notify;

import com.routemind.report.GeneratedReport;

import java.time.LocalDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers an alert into the app itself rather than to an inbox.
 *
 * This is the default channel for the demo and for any deployment without a mail server:
 * the alert lands in a table the UI polls, so the whole sense → reason → notify loop is
 * visible on screen with nothing external configured. Email remains available and is
 * unchanged; a subscription simply points at a different channel kind.
 *
 * Always {@link #available()} — the database is the one dependency the service already
 * requires, so unlike SMTP this channel cannot be half-configured.
 */
@Component
public class InAppChannel implements NotificationChannel {

    private final NamedParameterJdbcTemplate jdbc;

    public InAppChannel(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public String kind() { return "IN_APP"; }

    @Override
    public boolean available() { return true; }

    @Override
    public String send(String target, Recipient to, GeneratedReport r) {
        try {
            // Re-running the same alert for the same day REPLACES what is on screen rather
            // than stacking a second copy of the same finding beside it. Triggering from
            // the UI is meant to be safe to press twice; unread is reset because a refreshed
            // finding deserves to be looked at again.
            jdbc.update("""
                    INSERT INTO in_app_alert (persona_code, business_unit,
                                              title, body, severity, dedupe_key)
                    VALUES (:persona, :bu, :title, :body, :severity, :dedupeKey)
                    ON CONFLICT (dedupe_key) DO UPDATE
                       SET body       = EXCLUDED.body,
                           severity   = EXCLUDED.severity,
                           created_at = now(),
                           read_at    = NULL
                    """, new MapSqlParameterSource()
                    .addValue("persona", to.personaCode())
                    .addValue("bu", to.businessUnit() != null
                            ? to.businessUnit() : r.businessUnit())
                    .addValue("title", r.headline())
                    .addValue("body", r.body())
                    .addValue("severity", severityOf(r))
                    .addValue("dedupeKey", dedupeKey(to, r)));
            return null;   // delivered
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * One live alert per persona + business unit + headline + day.
     *
     * Deliberately includes the day: the same finding tomorrow is news again, the same
     * finding twice in one afternoon is noise. Built here rather than as a generated column
     * so the rule is visible in code and can be changed without a migration.
     */
    private static String dedupeKey(Recipient to, GeneratedReport r) {
        String bu = to.businessUnit() != null ? to.businessUnit()
                : r.businessUnit() != null ? r.businessUnit() : "";
        return to.personaCode() + "|" + bu + "|" + r.headline() + "|" + LocalDate.now();
    }

    /**
     * Severity drives how loudly the UI shows it. Derived from the report's own
     * severityScore rather than re-judged here, so the badge in the app and the ranking in
     * the report can never disagree.
     */
    private static String severityOf(GeneratedReport r) {
        if (r.severityScore() >= 60) return "CRITICAL";
        if (r.severityScore() >= 30) return "WARNING";
        return "INFO";
    }
}
