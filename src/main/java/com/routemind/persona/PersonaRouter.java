package com.routemind.persona;

import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.narrative.NarrativeService;
import com.routemind.rules.Finding;
import com.routemind.rules.InsightRanker;
import com.routemind.rules.RuleEvaluator;
import com.routemind.rules.RuleSetProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Right finding, right person, right words. This is the only place that knows
 * about personas — the metric and rule layers stay persona-agnostic.
 */
@Service
public class PersonaRouter {

    public record PersonaBundle(String persona,
                                String displayName,
                                String need,
                                String cadence,
                                String channel,
                                LocalDate from,
                                LocalDate to,
                                String businessUnit,
                                List<Finding> findings,
                                List<MetricWithContext> metrics) {}

    private final MetricService metrics;
    private final RuleEvaluator evaluator;
    private final InsightRanker ranker;
    private final NarrativeService narrative;
    private final RuleSetProperties rules;

    public PersonaRouter(MetricService metrics, RuleEvaluator evaluator, InsightRanker ranker,
                         NarrativeService narrative, RuleSetProperties rules) {
        this.metrics = metrics;
        this.evaluator = evaluator;
        this.ranker = ranker;
        this.narrative = narrative;
        this.rules = rules;
    }

    /** Metric ids this persona owns — config overrides the enum default. */
    public List<String> metricsFor(Persona p) {
        List<String> configured = rules.getPersonas().get(p.name());
        return configured == null || configured.isEmpty() ? p.defaultMetrics() : configured;
    }

    public PersonaBundle bundle(Persona p, LocalDate from, LocalDate to,
                                String businessUnit, int limit) {

        List<String> owned = metricsFor(p);

        List<MetricWithContext> all = metrics.all(from, to, businessUnit);
        List<MetricWithContext> mine = all.stream()
                .filter(m -> owned.contains(m.metric()))
                .toList();

        List<Finding> findings = evaluator.evaluate(mine).stream()
                .sorted(Comparator.comparingDouble(
                        (Finding f) -> f.materiality()
                                * ranker.personaRelevance(f.metricId(), p.name())).reversed())
                .limit(limit)
                .toList();

        return new PersonaBundle(p.name(), p.displayName(), p.need(),
                p.cadence().name(), p.channel().name(), from, to, businessUnit,
                narrative.narrateAll(findings, p.name()), mine);
    }

    /** All three personas in one pass — used by the dashboard and the scheduler. */
    public List<PersonaBundle> allPersonas(LocalDate from, LocalDate to,
                                           String businessUnit, int limit) {
        return java.util.Arrays.stream(Persona.values())
                .map(p -> bundle(p, from, to, businessUnit, limit))
                .toList();
    }
}
