package com.routemind.narrative;

import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.rules.Finding;
import org.springframework.stereotype.Component;

/**
 * Deterministic narrative. Zero cost, zero latency, always correct — and the
 * safety net whenever the LLM is disabled, rate-limited or wrong.
 */
@Component
public class TemplateNarrativeGenerator implements NarrativeGenerator {

    @Override
    public String narrate(Finding f, String persona) {
        StringBuilder sb = new StringBuilder(f.evidence());

        if (f.projection() != null && f.projection().projectedBreachDate() != null) {
            sb.append(" At the current pace this breaches around ")
              .append(f.projection().projectedBreachDate()).append('.');
        }

        String action = action(f);
        if (action != null) sb.append(' ').append(action);
        return sb.toString();
    }

    /** The "act" half of sense-reason-act — a concrete next step, human-approved. */
    public static String action(Finding f) {
        if (f.attribution() == null || f.attribution().isEmpty()) return null;
        Contribution top = f.attribution().get(0);
        return switch (f.metricId()) {
            case "ota" -> "Recommend an SLA review with " + top.member()
                    + ", which owns " + top.pct() + "% of late trips.";
            case "no_show_rate" -> "Recommend day-before confirmations and auto-releasing "
                    + "unconfirmed seats on " + top.member() + " routes.";
            case "cost_per_trip" -> "Recommend renegotiating the slab with " + top.member()
                    + ", which carries " + top.pct() + "% of spend.";
            case "experience" -> "Recommend driver coaching with " + top.member()
                    + ", source of " + top.pct() + "% of poor ratings.";
            case "safety_alerts_per_1k" -> "Recommend prioritising " + top.member()
                    + " incidents in the safety review.";
            case "seat_utilisation" -> "Recommend consolidating low-occupancy routes on "
                    + top.member() + " to cut empty seats.";
            case "ev_share" -> "Recommend shifting " + top.member()
                    + " trips onto electric vehicles first.";
            default -> null;
        };
    }

    @Override public int priority() { return 0; }
}
