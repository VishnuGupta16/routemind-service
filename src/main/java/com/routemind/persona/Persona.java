package com.routemind.persona;

import java.util.List;

/**
 * The three personas from the brief. Each is a LENS over the same engine, not a
 * separate application — defaults here, overridable per tenant in application.yml
 * (routemind.personas.*).
 */
public enum Persona {

    /** Day-to-day ops: vendor coordination, escalations, delay management. */
    TRANSPORT_MANAGER(
            "Transport manager",
            "Fast, actionable signals — what is going wrong right now and who to call",
            List.of("ota", "no_show_rate", "safety_alerts_per_1k"),
            Cadence.REALTIME, Channel.ALERT),

    /** Strategic: budget, SLA accountability, vendor strategy, leadership reporting. */
    FACILITIES_HEAD(
            "Transport & facilities head",
            "A coherent cost / safety / experience story, forwardable to leadership",
            List.of("cost_per_trip", "ota", "safety_alerts_per_1k", "experience",
                    "seat_utilisation", "ev_share"),
            Cadence.WEEKLY, Channel.REPORT),

    /** Shift-based: who made it, who was late, floor readiness. */
    LINE_MANAGER(
            "Team / line manager",
            "Shift-level readiness — who made it, who was late, and the knock-on",
            List.of("ota", "no_show_rate"),
            Cadence.PER_SHIFT, Channel.ALERT);

    public enum Cadence { REALTIME, PER_SHIFT, DAILY, WEEKLY }
    public enum Channel { ALERT, REPORT, DASHBOARD }

    private final String displayName;
    private final String need;
    private final List<String> defaultMetrics;
    private final Cadence cadence;
    private final Channel channel;

    Persona(String displayName, String need, List<String> defaultMetrics,
            Cadence cadence, Channel channel) {
        this.displayName = displayName;
        this.need = need;
        this.defaultMetrics = defaultMetrics;
        this.cadence = cadence;
        this.channel = channel;
    }

    public String displayName() { return displayName; }
    public String need() { return need; }
    public List<String> defaultMetrics() { return defaultMetrics; }
    public Cadence cadence() { return cadence; }
    public Channel channel() { return channel; }

    public static Persona of(String raw) {
        if (raw == null || raw.isBlank()) return FACILITIES_HEAD;
        return Persona.valueOf(raw.trim().toUpperCase().replace('-', '_'));
    }
}
