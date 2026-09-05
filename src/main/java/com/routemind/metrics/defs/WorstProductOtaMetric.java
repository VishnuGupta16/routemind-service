package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import com.routemind.sla.SlaPolicyService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On-time rate of the WEAKEST schedulable cab type.
 *
 * A group average of 96.6% hides a whole category: BUS runs at 92.9% across 99,643 trips
 * while CAB runs at 97.3%. Because the weak category is the smaller one, it can never move
 * the headline enough to be noticed. This metric reports the laggard directly.
 *
 * Rentals (SPOT_2.0) are excluded via `routemind.metric-exclusions.worst_product_ota` —
 * a rental is booked for a job rather than scheduled against a pickup, so it has no
 * on-time target and is not a fair comparison. Every trip is scored against its own
 * contractual SLA window, resolved from `sla_policy`.
 */
@Component
public class WorstProductOtaMetric implements MetricDefinition {

    /** Minimum trips before a cab type is judged — stops a 3-trip category "winning". */
    private static final int MIN_TRIPS = 500;

    public String id() { return "worst_product_ota"; }
    public String displayName() { return "On-time rate, weakest cab type"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.HIGHER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 92.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH by_type AS (
                    SELECT t.product_type,
                           count(*) AS trips,
                           100.0 * count(*) FILTER (
                               WHERE t.delay_minutes <= """ + SlaPolicyService.windowForTrip("t") + """
                           ) / NULLIF(count(*), 0) AS ota
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.product_type NOT IN (:excludedProducts)
                    GROUP BY t.product_type
                    HAVING count(*) >= """ + MIN_TRIPS + """
                )
                SELECT trips AS sample_size, ota AS value
                FROM by_type ORDER BY ota ASC LIMIT 1
                """;
        return Sql.point(jdbc, sql, q);
    }

    /** Every schedulable cab type ranked, so the laggard is shown in context. */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH by_type AS (
                    SELECT t.product_type,
                           count(*) FILTER (
                               WHERE t.delay_minutes > """ + SlaPolicyService.windowForTrip("t") + """
                           ) AS late,
                           count(*) AS trips
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.product_type NOT IN (:excludedProducts)
                    GROUP BY t.product_type
                    HAVING count(*) >= """ + MIN_TRIPS + """
                )
                SELECT product_type AS member, late AS cnt,
                       100.0 * late / NULLIF(trips, 0) AS pct
                FROM by_type
                ORDER BY (late::numeric / NULLIF(trips, 0)) DESC
                LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }

    /** `pct` is each type's OWN late-rate, not a share of the total — label it honestly. */
    public String attributionDimension() { return "cab type (own late-rate %)"; }
}
