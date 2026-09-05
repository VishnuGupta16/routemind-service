package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Sustainability: share of trips run on electric vehicles. */
@Component
public class EvShareMetric implements MetricDefinition {

    public String id() { return "ev_share"; }
    public String displayName() { return "Electric vehicle share"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.HIGHER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 25.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                SELECT count(*) AS sample_size,
                       100.0 * count(*) FILTER (WHERE fuel_type = 'Electric')
                             / NULLIF(count(*), 0) AS value
                FROM trips
                WHERE trip_date BETWEEN :from AND :to
                """ + Sql.BU + Sql.PRODUCTS;
        return Sql.point(jdbc, sql, q);
    }

    /** Vendors with the most non-electric trips — where switching helps most. */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH ice AS (
                    SELECT vendor FROM trips
                    WHERE trip_date BETWEEN :from AND :to
                      AND fuel_type <> 'Electric'
                """ + Sql.BU + Sql.PRODUCTS + """
                )
                SELECT vendor AS member, count(*) AS cnt,
                       100.0 * count(*) / NULLIF(sum(count(*)) OVER (), 0) AS pct
                FROM ice GROUP BY vendor ORDER BY cnt DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }
}
