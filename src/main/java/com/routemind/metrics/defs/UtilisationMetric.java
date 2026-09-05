package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Seat utilisation: employees carried vs cab capacity. Drives cost efficiency. */
@Component
public class UtilisationMetric implements MetricDefinition {

    public String id() { return "seat_utilisation"; }
    public String displayName() { return "Seat utilisation"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.HIGHER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 70.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size,
                       100.0 * sum(actual_employee_cnt)::numeric
                             / NULLIF(sum(actual_cab_capacity), 0) AS value
                FROM trips
                WHERE trip_date BETWEEN :from AND :to
                  AND actual_cab_capacity > 0
                """ + Sql.BU + Sql.PRODUCTS;
        return Sql.point(jdbc, sql, q);
    }

    /** Vendors running the emptiest vehicles (largest wasted seat count). */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH waste AS (
                    SELECT vendor,
                           sum(greatest(actual_cab_capacity - actual_employee_cnt, 0)) AS empty_seats
                    FROM trips
                    WHERE trip_date BETWEEN :from AND :to
                      AND actual_cab_capacity > 0
                """ + Sql.BU + Sql.PRODUCTS + """
                    GROUP BY vendor
                )
                SELECT vendor AS member, empty_seats AS cnt,
                       100.0 * empty_seats / NULLIF(sum(empty_seats) OVER (), 0) AS pct
                FROM waste ORDER BY empty_seats DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
