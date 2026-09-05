package com.routemind.notify;

import com.routemind.notify.NotificationChannel.Recipient;
import com.routemind.report.GeneratedReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fans one report out to everyone subscribed to its alert, over whatever channel each of
 * them chose, and records the outcome per recipient.
 *
 * Per-recipient, not per-report, is the important part: one bad mailbox must not look like
 * a failed report, and a report that reached four of five people needs to be visibly
 * different from one that reached nobody.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, NotificationChannel> channels = new LinkedHashMap<>();

    public NotificationService(NamedParameterJdbcTemplate jdbc, List<NotificationChannel> all) {
        this.jdbc = jdbc;
        for (NotificationChannel c : all) channels.put(c.kind(), c);
    }

    /**
     * @return a per-outcome tally, e.g. {SENT=3, SKIPPED=1}
     */
    public Map<String, Integer> dispatch(long reportId, GeneratedReport report) {
        List<Map<String, Object>> subs = subscribers(report.alertCode(), report.businessUnit());
        Map<String, Integer> tally = new LinkedHashMap<>();

        if (subs.isEmpty()) {
            log.info("report {} ({}) has no active subscribers", reportId, report.alertCode());
            return tally;
        }

        for (Map<String, Object> s : subs) {
            String kind = String.valueOf(s.get("channel_kind"));
            String target = String.valueOf(s.get("email"));
            NotificationChannel channel = channels.get(kind);

            String status, error = null;
            if (channel == null) {
                status = "SKIPPED";
                error = "no implementation for channel " + kind;
            } else if (!channel.available()) {
                status = "SKIPPED";
                error = kind + " channel is not configured";
            } else {
                Recipient to = new Recipient(
                        ((Number) s.get("recipient_id")).longValue(), target,
                        (String) s.get("display_name"), (String) s.get("persona_code"),
                        (String) s.get("persona_name"), (String) s.get("business_unit"));
                error = channel.send(target, to, report);
                status = error == null ? "SENT" : "FAILED";
            }

            record(reportId, s.get("subscription_id"), kind, target, status, error);
            tally.merge(status, 1, Integer::sum);
        }
        return tally;
    }

    /**
     * Who should receive this. A subscription can narrow the alert's scope to one business
     * unit; a subscription with no business unit receives everything.
     */
    private List<Map<String, Object>> subscribers(String alertCode, String businessUnit) {
        return jdbc.queryForList("""
                SELECT sub.id AS subscription_id, r.id AS recipient_id, r.email,
                       r.display_name, ch.kind AS channel_kind,
                       p.code AS persona_code, p.name AS persona_name,
                       COALESCE(sub.business_unit, r.business_unit) AS business_unit
                FROM alert_subscription sub
                JOIN alert_definition a   ON a.id  = sub.alert_definition_id
                JOIN recipient r          ON r.id  = sub.recipient_id
                JOIN notification_channel ch ON ch.id = sub.channel_id
                JOIN persona p            ON p.id  = sub.persona_id
                WHERE a.code = :alertCode
                  AND sub.active AND r.active AND ch.active AND a.active
                  AND (COALESCE(sub.business_unit, r.business_unit) IS NULL
                       OR :bu IS NULL
                       OR COALESCE(sub.business_unit, r.business_unit) = :bu)
                ORDER BY r.email
                """, new MapSqlParameterSource("alertCode", alertCode).addValue("bu", businessUnit));
    }

    private void record(long reportId, Object subscriptionId, String kind, String target,
                        String status, String error) {
        jdbc.update("""
                INSERT INTO notification_log (report_id, subscription_id, channel_kind,
                                              target, status, error)
                VALUES (:reportId, :subId, :kind, :target, :status, :error)
                """, new MapSqlParameterSource()
                .addValue("reportId", reportId)
                .addValue("subId", subscriptionId)
                .addValue("kind", kind)
                .addValue("target", target)
                .addValue("status", status)
                .addValue("error", error));
    }

    /** Which channel kinds this build can actually deliver over — shown in the admin UI. */
    public Map<String, Boolean> channelStatus() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        channels.forEach((k, c) -> out.put(k, c.available()));
        return out;
    }
}
