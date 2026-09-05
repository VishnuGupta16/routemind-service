package com.routemind.notify;

import com.routemind.report.GeneratedReport;

/**
 * A delivery channel. Email today; Slack, Teams or a webhook are new implementations and
 * nothing else — the scheduler, the generators and the subscription tables all reference a
 * channel by {@link #kind()} and never by an address.
 */
public interface NotificationChannel {

    /** Matches notification_channel.kind — EMAIL, SLACK, WEBHOOK … */
    String kind();

    /**
     * True when this channel is actually configured. A channel that cannot send must say so
     * rather than throwing at send time: the report is already generated and stored by then,
     * and a misconfigured mailbox should be logged as SKIPPED, not lose the report.
     */
    boolean available();

    /**
     * @param target the address / URL for this recipient
     * @return null on success, otherwise the reason it failed — recorded in notification_log
     */
    String send(String target, Recipient recipient, GeneratedReport report);

    /** Who it is going to, and in which persona's voice. */
    record Recipient(long id, String email, String displayName, String personaCode,
                     String personaName, String businessUnit) {}
}
