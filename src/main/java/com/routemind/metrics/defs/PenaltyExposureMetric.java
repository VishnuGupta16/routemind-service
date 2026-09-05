package com.routemind.metrics.defs;

import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SLA penalties as a share of spend — a RISK metric, not a performance one.
 *
 * Penalties arrive in two forms and both count:
 *   TRIP_PENALTY     a negative line against a real trip    (156 lines, −₹14.67M)
 *   MONTHLY_PENALTY  a negative monthly "OverHead" line     ( 33 lines, −₹0.83M)
 * Together: 189 lines, −₹15.50M, across 7 of the 21 vendors running more than 5,000 trips.
 *
 * WHY THIS IS FRAMED AS RISK RATHER THAN PERFORMANCE
 * --------------------------------------------------
 * The obvious reading — "penalties tell you who the bad vendors are" — is not supported by
 * this data, and saying so would be the easiest way to put a false claim in front of a
 * customer. Measured across those 21 vendors:
 *
 *     corr(OTA, penalty as % of spend)        = −0.06   (i.e. none)
 *     mean OTA, penalised vendors             = 96.60%
 *     mean OTA, never-penalised vendors       = 96.62%   (indistinguishable)
 *     the worst-OTA vendor                    = ₹0 penalties
 *     the largest penalty, ₹804,703           = a vendor at 96.3% OTA, better than average
 *
 * So penalties are not being levied for punctuality. What they ARE for is unconfirmed
 * (open-questions.md A1) — vehicle or driver non-compliance, safety, or billing disputes
 * are all plausible.
 *
 * WHAT THE EXPOSURE ACTUALLY LOOKS LIKE — and it is extreme
 * --------------------------------------------------------
 * ₹14.66M of the ₹15.50M total, 94.6%, sits on ONE vendor (Meera Lebedev Travel), ONE
 * contract (6S-PREMIUMNEW), ONE office (Pinecrest) and mostly ONE cycle (May 2026) — against
 * only ₹3.46M of positive billing to that vendor. A second vendor has 11 penalty lines and
 * no positive billing and no trips at all. Both are flagged in open-questions.md A1 rather
 * than narrated as fact: a genuine contract blow-up and a data-attribution artefact look
 * identical from here, and the difference matters enormously to whoever reads the report.
 *
 * The metric is built to survive both readings — see the NULLS LAST note in attribute().
 */
@Component
public class PenaltyExposureMetric implements MetricDefinition {

    /** Both penalty forms. Excluding the monthly ones would understate exposure by 5.4%. */
    private static final String PENALTIES =
            " line_kind IN ('TRIP_PENALTY', 'MONTHLY_PENALTY') ";

    public String id() { return "penalty_exposure"; }
    public String displayName() { return "SLA penalties as % of spend"; }
    public String unit() { return "percent"; }
    public Direction direction() { return Direction.LOWER_IS_BETTER; }

    /**
     * Deliberately low. Penalties are rare and lumpy, so this is a tripwire rather than an
     * average to manage down — the point is to notice a month where exposure jumps, not to
     * report a number that is 0.0 almost every month.
     */
    public double target(Targets t) { return t.targetFor(id(), 0.5); }

    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        // sample_size is the COUNT OF PENALTY LINES, not of billing lines: a percentage
        // built on 189 events should not be presented as if it rested on 620,942.
        String sql = """
                SELECT count(*) FILTER (WHERE""" + PENALTIES + """
                       ) AS sample_size,
                       100.0 * abs(sum(trip_cost) FILTER (WHERE""" + PENALTIES + """
                       )) / NULLIF(sum(trip_cost) FILTER (WHERE trip_cost > 0), 0) AS value
                FROM billing
                WHERE cycle_start <= :to AND cycle_end >= :from
                """ + Sql.BU;
        return Sql.point(jdbc, sql, q);
    }

    /**
     * Which vendors carry the exposure, as a share of THEIR OWN spend.
     *
     * Ranking by absolute penalty would just rediscover which vendor is biggest. As a share
     * of their own billing it is a real signal: one vendor at 3.14% against a field where
     * nobody else exceeds 0.04%.
     */
    public List<Contribution> attribute(MetricQuery q, NamedParameterJdbcTemplate jdbc) {
        String sql = """
                WITH v AS (
                    SELECT vendor,
                           count(*) FILTER (WHERE""" + PENALTIES + """
                           ) AS penalty_lines,
                           abs(sum(trip_cost) FILTER (WHERE""" + PENALTIES + """
                           )) AS penalised,
                           sum(trip_cost) FILTER (WHERE trip_cost > 0) AS billed
                    FROM billing
                    WHERE cycle_start <= :to AND cycle_end >= :from
                """ + Sql.BU + """
                    GROUP BY vendor
                )
                SELECT vendor AS member, penalty_lines AS cnt,
                       100.0 * penalised / NULLIF(billed, 0) AS pct
                FROM v
                WHERE penalised > 0
                -- NULLS LAST matters: one vendor in this data has penalties and NO positive
                -- billing at all, so the ratio is NULL. Postgres sorts NULLs FIRST on DESC,
                -- which would put an uncomputable vendor at the top of the list.
                ORDER BY penalised / NULLIF(billed, 0) DESC NULLS LAST, penalised DESC
                LIMIT :topN
                """;
        return Sql.contributions(jdbc, sql, q, 5);
    }

    /** `pct` is each vendor's own penalty rate, not a share of the total — say so. */
    public String attributionDimension() { return "vendor (penalties as % of own spend)"; }
}
