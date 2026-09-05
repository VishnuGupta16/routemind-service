package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Average billed cost per trip. Overhead lines are excluded from the average. */
@Component
public class CostPerTripMetric implements MetricDefinition {

    public String id() { return "cost_per_trip"; }
    public String displayName() { return "Cost per trip"; }
    public String unit() { return "currency"; }
    public Direction direction() { return Direction.LOWER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 1000.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size, avg(trip_cost) AS value
                FROM billing
                WHERE NOT is_overhead
                  AND cycle_start <= :to AND cycle_end >= :from
                """ + Sql.BU;
        return Sql.point(jdbc, sql, q);
    }

    /** Where the money goes — vendor share of total spend (overhead included). */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH spend AS (
                    SELECT vendor, sum(trip_cost) AS total
                    FROM billing
                    WHERE cycle_start <= :to AND cycle_end >= :from
                """ + Sql.BU + """
                    GROUP BY vendor
                )
                SELECT vendor AS member, total::bigint AS cnt,
                       100.0 * total / NULLIF(sum(total) OVER (), 0) AS pct
                FROM spend ORDER BY total DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
