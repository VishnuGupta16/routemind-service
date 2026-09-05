package com.routemind.rules;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.Projection;
import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition.Contribution;

import java.time.LocalDate;
import java.util.List;

/**
 * Something the system decided is worth a human's attention.
 * Carries all its evidence, so any narrative written from it is verifiable.
 */
public record Finding(
        String id,
        String metricId,
        String displayName,
        Severity severity,
        String reason,                 // why it fired: BELOW_TARGET / FALLING / PROJECTED_BREACH
        LocalDate from,
        LocalDate to,
        String businessUnit,
        double value,
        double target,
        Double priorValue,
        Status status,
        String attributionDimension,
        List<Contribution> attribution,
        Projection projection,
        double materiality,
        String evidence,
        String narrative) {

    public enum Severity { HIGH, MEDIUM, LOW }

    public static Finding from(MetricWithContext m, Severity sev, String reason,
                               double materiality) {
        String id = "F-" + Math.abs((m.metric() + m.from() + m.to() +
                String.valueOf(m.businessUnit())).hashCode() % 100000);
        return new Finding(id, m.metric(), m.displayName(), sev, reason,
                m.from(), m.to(), m.businessUnit(), m.value(), m.target(),
                m.priorValue(), m.status(), m.attributionDimension(),
                m.topContributors(), m.projection(), materiality,
                m.headline(), m.headline());
    }

    public Finding withNarrative(String text) {
        return new Finding(id, metricId, displayName, severity, reason, from, to,
                businessUnit, value, target, priorValue, status, attributionDimension,
                attribution, projection, materiality, evidence, text);
    }

    /** Stable key used for alert de-duplication / cooldown. */
    public String dedupeKey() {
        return metricId + "|" + businessUnit + "|" + reason;
    }
}
