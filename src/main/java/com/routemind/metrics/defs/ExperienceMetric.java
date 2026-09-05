package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Employee experience: mean of the rated dimensions (0 = "not rated" was already
 * normalised to NULL during ETL, so it cannot drag the average down).
 */
@Component
public class ExperienceMetric implements MetricDefinition {

    public String id() { return "experience"; }
    public String displayName() { return "Employee experience"; }
    public String unit() { return "rating"; }
    public Direction direction() { return Direction.HIGHER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 4.5); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size,
                       avg((coalesce(route_rating,0) + coalesce(driver_rating,0)
                          + coalesce(cab_rating,0)  + coalesce(safety_rating,0))::numeric
                           / NULLIF( (route_rating IS NOT NULL)::int
                                   + (driver_rating IS NOT NULL)::int
                                   + (cab_rating IS NOT NULL)::int
                                   + (safety_rating IS NOT NULL)::int, 0)) AS value
                FROM feedback
                WHERE trip_at::date BETWEEN :from AND :to
                """ + Sql.BU;
        return Sql.point(jdbc, sql, q);
    }

    /** Who owns the poor experiences (ratings <= 2). */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH bad AS (
                    SELECT t.vendor
                    FROM feedback f
                    JOIN trips t ON t.trip_id = f.trip_id
                    WHERE f.trip_at::date BETWEEN :from AND :to
                      AND coalesce(f.driver_rating, f.route_rating, 5) <= 2
                      AND (CAST(:bu AS text) IS NULL OR f.business_unit = :bu)
                )
                SELECT vendor AS member, count(*) AS cnt,
                       100.0 * count(*) / NULLIF(sum(count(*)) OVER (), 0) AS pct
                FROM bad GROUP BY vendor ORDER BY cnt DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
