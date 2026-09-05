package com.routemind.metrics;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.Projection;
import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.MetricDefinition.*;
import com.routemind.metrics.spi.Sql;
import com.routemind.predict.ErrorBudgetService;
import com.routemind.rules.RuleSetProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles every metric WITH CONTEXT: value + target + prior period + attribution
 * + projection. Works for any registered {@link MetricDefinition} — no metric is
 * special-cased here.
 */
@Service
public class MetricService {

    private final Map<String, MetricDefinition> registry = new LinkedHashMap<>();
    private final NamedParameterJdbcTemplate jdbc;
    private final RuleSetProperties rules;
    private final ErrorBudgetService budget;

    public MetricService(List<MetricDefinition> definitions,
                         NamedParameterJdbcTemplate jdbc,
                         RuleSetProperties rules,
                         ErrorBudgetService budget) {
        definitions.forEach(d -> registry.put(d.id(), d));
        this.jdbc = jdbc;
        this.rules = rules;
        this.budget = budget;
    }

    public List<String> metricIds() { return List.copyOf(registry.keySet()); }

    public Optional<MetricWithContext> metric(String id, LocalDate from, LocalDate to, String bu) {
        return Optional.ofNullable(registry.get(id)).map(d -> compute(d, from, to, bu));
    }

    /** Every metric, for the dashboard and for the rule evaluator. */
    public List<MetricWithContext> all(LocalDate from, LocalDate to, String bu) {
        return registry.values().stream().map(d -> compute(d, from, to, bu)).toList();
    }

    private MetricWithContext compute(MetricDefinition def, LocalDate from, LocalDate to, String bu) {
        // exclusions are resolved PER METRIC — a rental cab has no on-time target but
        // still counts towards cost, fuel mix and safety
        MetricQuery q = new MetricQuery(from, to, bu, rules.getSla().getOtaWindowMinutes(),
                rules.exclusionsFor(def.id()));

        MetricPoint current = def.compute(q, jdbc);
        MetricPoint prior = def.compute(q.shiftedBack(), jdbc);
        Double priorValue = prior.isEmpty() ? null : prior.value();

        double target = def.target(rules);
        boolean higherBetter = def.direction() == Direction.HIGHER_IS_BETTER;

        Double vsTarget = Sql.round1(current.value() - target);
        Double vsPrior = priorValue == null ? null : Sql.round1(current.value() - priorValue);

        List<Contribution> contributors =
                current.isEmpty() ? List.of() : def.attribute(q, jdbc);

        Status status = status(current.value(), target, vsPrior, higherBetter);

        Projection projection = current.isEmpty() ? null
                : budget.project(current.value(), target, def.direction(), from, to, to);

        String headline = Narratives.headline(def, current, target, priorValue,
                vsPrior, contributors, status);

        return new MetricWithContext(
                def.id(), def.displayName(), def.unit(), def.direction().name(),
                from, to, bu, current.sampleSize(), current.value(), target,
                priorValue, vsTarget, vsPrior, status,
                def.attributionDimension(), contributors, projection, headline);
    }

    /**
     * Ceiling on the at-risk band, as a percentage of the target. Keeps the configured
     * absolute margin meaningful across metrics measured in points, rupees and ratings.
     */
    private static final double AT_RISK_MAX_PCT_OF_TARGET = 5.0;

    private Status status(double value, double target, Double vsPrior, boolean higherBetter) {
        boolean breach = higherBetter ? value < target : value > target;
        if (breach) return Status.BREACH;
        double distance = higherBetter ? value - target : target - value;

        // The at-risk margin is an absolute number of units, which only makes sense near
        // the scale it was chosen for: 2.0 points is a sensible warning band around a 95%
        // OTA target (2% of it) and a nonsensical one around a 9% EV floor (22% of it),
        // where it marked a metric sitting 21% clear of target as AT_RISK. Cap it at a
        // proportion of the target so one setting behaves the same way on every scale.
        double margin = Math.min(rules.getSla().getAtRiskMargin(),
                Math.abs(target) * (AT_RISK_MAX_PCT_OF_TARGET / 100.0));
        if (distance <= margin) return Status.AT_RISK;
        double drop = rules.getTrigger().getTrendDropUnits();
        if (vsPrior != null && (higherBetter ? vsPrior <= -drop : vsPrior >= drop)) {
            return Status.AT_RISK;
        }
        return Status.OK;
    }
}
