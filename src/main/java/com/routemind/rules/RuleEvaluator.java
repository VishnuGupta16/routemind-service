package com.routemind.rules;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.Status;
import com.routemind.rules.Finding.Severity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which metrics are worth raising. Pure rules — no AI.
 * Three rule families, all driven by the declarative RuleSet:
 *   BELOW_TARGET     — value is on the wrong side of the SLA
 *   FALLING          — dropped materially vs the previous period
 *   PROJECTED_BREACH — the error budget says we won't make it (predictive)
 */
@Service
public class RuleEvaluator {

    private final RuleSetProperties rules;
    private final InsightRanker ranker;

    public RuleEvaluator(RuleSetProperties rules, InsightRanker ranker) {
        this.rules = rules;
        this.ranker = ranker;
    }

    public List<Finding> evaluate(List<MetricWithContext> metrics) {
        List<Finding> out = new ArrayList<>();

        for (MetricWithContext m : metrics) {
            if (m.sampleSize() == 0) continue;
            boolean higherBetter = "HIGHER_IS_BETTER".equals(m.direction());

            if (m.status() == Status.BREACH) {
                out.add(build(m, Severity.HIGH, "BELOW_TARGET"));
                continue;                       // one finding per metric, most severe wins
            }

            double drop = rules.getTrigger().getTrendDropUnits();
            boolean falling = m.vsPrior() != null &&
                    (higherBetter ? m.vsPrior() <= -drop : m.vsPrior() >= drop);
            if (falling) {
                out.add(build(m, Severity.MEDIUM, "FALLING"));
                continue;
            }

            if (m.projection() != null && m.projection().status() == Status.BREACH) {
                out.add(build(m, Severity.HIGH, "PROJECTED_BREACH"));
                continue;
            }

            if (m.status() == Status.AT_RISK) {
                out.add(build(m, Severity.MEDIUM, "AT_RISK"));
            }
        }

        out.sort(Comparator.comparingDouble(Finding::materiality).reversed());
        return out;
    }

    private Finding build(MetricWithContext m, Severity sev, String reason) {
        return Finding.from(m, sev, reason, ranker.materiality(m, sev));
    }
}
