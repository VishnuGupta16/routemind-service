package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import com.routemind.sla.SlaPolicyService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On-time arrival — scored against each trip's OWN contractual SLA.
 *
 * The window is not a global constant: it is resolved per trip from `sla_policy`, so a
 * premium vendor committed to 5 minutes and a shuttle operator committed to 15 are each
 * judged on what they actually signed. `:window` remains the fallback for trips no
 * policy covers, which keeps behaviour identical when no policies are configured.
 */
@Component
public class OtaMetric implements MetricDefinition {

    public String id() { return "ota"; }
    public String displayName() { return "On-time arrival"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.HIGHER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 95.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size,
                       100.0 * count(*) FILTER (
                           WHERE t.delay_minutes <= """ + SlaPolicyService.windowForTrip("t") + """
                       ) / NULLIF(count(*), 0) AS value
                FROM trips t
                WHERE t.trip_date BETWEEN :from AND :to
                  AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                  AND t.product_type NOT IN (:excludedProducts)
                """;
        return Sql.point(jdbc, sql, q);
    }

    /** Which vendors own the late trips — again judged against their own SLA. */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH late AS (
                    SELECT t.vendor FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND t.delay_minutes > """ + SlaPolicyService.windowForTrip("t") + """
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.product_type NOT IN (:excludedProducts)
                )
                SELECT vendor AS member, count(*) AS cnt,
                       100.0 * count(*) / NULLIF(sum(count(*)) OVER (), 0) AS pct
                FROM late GROUP BY vendor ORDER BY cnt DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
