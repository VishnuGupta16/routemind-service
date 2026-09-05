package com.routemind.rules;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.rules.Finding.Severity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Decides which few findings matter — the judgment layer that a dashboard lacks.
 * materiality = gap size × confidence (sample size) × severity weight.
 */
@Service
public class InsightRanker {

    private final RuleSetProperties rules;

    public InsightRanker(RuleSetProperties rules) { this.rules = rules; }

    public double materiality(MetricWithContext m, Severity sev) {
        double gap = m.vsTarget() == null ? 0 : Math.abs(m.vsTarget());
        // normalise: a 10-point miss is "full" weight
        double gapScore = Math.min(gap / 10.0, 1.0);
        // more data = more confidence, but with diminishing returns
        double confidence = Math.min(Math.log10(Math.max(m.sampleSize(), 1)) / 6.0, 1.0);
        double sevWeight = switch (sev) {
            case HIGH -> 1.0;
            case MEDIUM -> 0.6;
            case LOW -> 0.3;
        };
        return round3(gapScore * 0.5 + confidence * 0.2 + sevWeight * 0.3);
    }

    /** Re-rank for a persona: metrics they own float to the top. */
    public double personaRelevance(String metricId, String persona) {
        List<String> owned = rules.getPersonas().getOrDefault(persona, List.of());
        return owned.isEmpty() || owned.contains(metricId) ? 1.0 : 0.25;
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
