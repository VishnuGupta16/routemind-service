package com.routemind.metrics.spi;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * A metric is a plugin. Implement this, annotate the class {@code @Component},
 * and it is automatically registered, benchmarked, evaluated by rules, narrated
 * and routed to personas. Adding a metric requires no change anywhere else.
 */
public interface MetricDefinition {

    /** stable id, e.g. "ota" */
    String id();

    /** human label, e.g. "On-time arrival" */
    String displayName();

    /** "percent" | "currency" | "rating" | "rate" */
    String unit();

    Direction direction();

    /** Target for this metric, read from the declarative RuleSet. */
    double target(Targets targets);

    /** Compute the metric for a period. */
    MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc);

    /**
     * Who is responsible for the "bad" side of this metric (usually by vendor).
     * Empty list means attribution is not meaningful for this metric.
     */
    default List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        return List.of();
    }

    /** The dimension attribution is sliced by — shown in narratives. */
    default String attributionDimension() { return "vendor"; }

    // ------------------------------------------------------------ nested types

    enum Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

    /**
     * What to compute over. businessUnit == null means all tenants.
     *
     * excludedProductTypes is resolved PER METRIC from
     * `routemind.metric-exclusions.<metricId>`, because relevance differs by metric.
     * SPOT_2.0 is a rental cab: booked for a job rather than scheduled against a pickup,
     * so it has no on-time target and is excluded from OTA — but it still costs money,
     * has a vendor and can raise safety alerts, so it stays in those metrics.
     */
    record MetricQuery(LocalDate from, LocalDate to, String businessUnit,
                       int otaWindowMinutes, List<String> excludedProductTypes) {

        public MetricQuery shiftedBack() {
            long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            LocalDate priorTo = from.minusDays(1);
            return new MetricQuery(priorTo.minusDays(days - 1), priorTo, businessUnit,
                    otaWindowMinutes, excludedProductTypes);
        }

        /** Same window, but scoped TO the excluded products instead of away from them. */
        public MetricQuery onlyExcluded() {
            return new MetricQuery(from, to, businessUnit, otaWindowMinutes, List.of());
        }
    }

    record MetricPoint(double value, long sampleSize) {
        public static final MetricPoint EMPTY = new MetricPoint(0, 0);
        public boolean isEmpty() { return sampleSize == 0; }
    }

    /** e.g. vendor X owns 13.3% of all late trips. */
    record Contribution(String member, long count, double pct) {}

    /** Read-only view of configured targets so metrics don't depend on Spring config types. */
    interface Targets {
        double targetFor(String metricId, double fallback);
    }
}
