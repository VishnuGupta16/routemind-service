package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Share of booked seats where the employee did not board. Lower is better. */
@Component
public class NoShowMetric implements MetricDefinition {

    public String id() { return "no_show_rate"; }
    public String displayName() { return "No-show rate"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.LOWER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 3.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size,
                       100.0 * count(*) FILTER (WHERE is_no_show)
                             / NULLIF(count(*), 0) AS value
                FROM trip_employees
                WHERE trip_date BETWEEN :from AND :to
                """ + Sql.BU + Sql.PRODUCTS;
        return Sql.point(jdbc, sql, q);
    }

    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH ns AS (
                    SELECT t.vendor
                    FROM trip_employees e
                    JOIN trips t ON t.trip_id = e.trip_id
                    WHERE e.trip_date BETWEEN :from AND :to
                      AND e.is_no_show
                      AND e.product_type NOT IN (:excludedProducts)
                      AND (:bu IS NULL OR e.business_unit = :bu)
                )
                SELECT vendor AS member, count(*) AS cnt,
                       100.0 * count(*) / NULLIF(sum(count(*)) OVER (), 0) AS pct
                FROM ns GROUP BY vendor ORDER BY cnt DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
