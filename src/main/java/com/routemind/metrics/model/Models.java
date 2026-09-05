package com.routemind.metrics.model;

import com.routemind.metrics.spi.MetricDefinition.Contribution;

import java.time.LocalDate;
import java.util.List;

/** Value types for the metric layer. Records = immutable, JSON-friendly. */
public final class Models {
    private Models() {}

    public enum Status { OK, AT_RISK, BREACH }

    /** Period-end projection from the error-budget calculation (deterministic). */
    public record Projection(double projectedValue,
                             double budgetUsedPct,
                             double burnRate,
                             LocalDate projectedBreachDate,
                             Status status) {}

    /**
     * A metric that ALWAYS carries context — the mandatory requirement.
     * There is no path that returns a bare number.
     */
    public record MetricWithContext(
            String metric,
            String displayName,
            String unit,
            String direction,
            LocalDate from,
            LocalDate to,
            String businessUnit,
            long sampleSize,
            double value,
            double target,
            Double priorValue,
            Double vsTarget,
            Double vsPrior,
            Status status,
            String attributionDimension,
            List<Contribution> topContributors,
            Projection projection,
            String headline) {}

    public record TableCount(String table, long rows) {}
}
