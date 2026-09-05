package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Safety alerts raised per 1,000 trips. Lower is better. */
@Component
public class SafetyMetric implements MetricDefinition {

    public String id() { return "safety_alerts_per_1k"; }
    public String displayName() { return "Safety alerts per 1,000 trips"; }
    public String unit() { return "rate"; }
    public Direction direction() { return Direction.LOWER_IS_BETTER; }
    public double target(Targets t) { return t.targetFor(id(), 50.0); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH t AS (
                    SELECT count(*) AS trips FROM trips
                    WHERE trip_date BETWEEN :from AND :to
                """ + Sql.BU + """
                ), a AS (
                    SELECT count(*) AS alerts FROM alerts
                    WHERE start_time::date BETWEEN :from AND :to
                """ + Sql.BU + """
                )
                SELECT t.trips AS sample_size,
                       1000.0 * a.alerts / NULLIF(t.trips, 0) AS value
                FROM t, a
                """;
        return Sql.point(jdbc, sql, q);
    }

    /** Which event types dominate — more actionable than vendor here. */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH ev AS (
                    SELECT event_type FROM alerts
                    WHERE start_time::date BETWEEN :from AND :to
                """ + Sql.BU + """
                )
                SELECT event_type AS member, count(*) AS cnt,
                       100.0 * count(*) / NULLIF(sum(count(*)) OVER (), 0) AS pct
                FROM ev GROUP BY event_type ORDER BY cnt DESC LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 3);
    }

    public String attributionDimension() { return "event type"; }
}
