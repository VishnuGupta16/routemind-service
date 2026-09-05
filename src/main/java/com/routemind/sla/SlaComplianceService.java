package com.routemind.sla;

import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * The vendor scorecard: every vendor × cab type × shift measured against the SLA IT
 * actually signed, not against a single group number.
 *
 * This is the point of per-vendor SLAs. A vendor at 94% is failing if it committed to
 * 97% and comfortably passing if it committed to 90% — the group average of 96.4% says
 * nothing useful about either. Only this view supports a contract conversation.
 */
@Service
public class SlaComplianceService {

    public record ComplianceRow(String vendor,
                                String businessUnit,
                                String productType,
                                String shiftType,
                                String shiftBand,
                                long trips,
                                long lateTrips,
                                double otaPct,
                                int windowMinutes,
                                double target,
                                double tolerancePct,
                                double vsTarget,
                                String status,          // MET | AT_RISK | BREACH
                                String slaName,
                                String slaScope) {}

    private final NamedParameterJdbcTemplate jdbc;

    public SlaComplianceService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param groupBy "vendor" (default), "vendor_product", "vendor_band" or "vendor_shift"
     *
     * "vendor_band" is the one to reach for. Grouping by exact shift produces 1,316 rows
     * across 100 clock times, most too small to judge; grouping by band produces 163 and
     * still separates the real signal — morning runs at 92.3% against 99.4% early.
     */
    public List<ComplianceRow> compliance(LocalDate from, LocalDate to, String businessUnit,
                                          String groupBy, int defaultWindow, int minTrips) {

        String extraCol, extraName;
        switch (groupBy == null ? "vendor" : groupBy) {
            case "vendor_product" -> { extraCol = "t.product_type"; extraName = "product_type"; }
            case "vendor_shift"   -> { extraCol = "t.shift_type";   extraName = "shift_type"; }
            case "vendor_band"    -> { extraCol = "t.shift_band";   extraName = "shift_band"; }
            default               -> { extraCol = "NULL::text";     extraName = "extra"; }
        }

        // Resolve each trip's own SLA, then aggregate. The policy is joined per trip so
        // a vendor whose SLA changed mid-period is scored correctly on both sides of it.
        String sql = """
                WITH scored AS (
                    SELECT t.vendor, t.business_unit, %s AS %s,
                           t.delay_minutes,
                           p.ota_window_minutes, p.ota_target, p.tolerance_pct,
                           p.name AS sla_name,
                           p.vendor AS s_vendor, p.business_unit AS s_bu,
                           p.product_type AS s_product, p.shift_type AS s_shift
                    FROM trips t
                    LEFT JOIN LATERAL (
                        SELECT s.* FROM sla_policy s
                        WHERE %s
                        %s
                        LIMIT 1
                    ) p ON TRUE
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                )
                SELECT vendor, business_unit, %s,
                       count(*) AS trips,
                       count(*) FILTER (
                           WHERE delay_minutes > COALESCE(ota_window_minutes, :window)
                       ) AS late_trips,
                       100.0 * count(*) FILTER (
                           WHERE delay_minutes <= COALESCE(ota_window_minutes, :window)
                       ) / NULLIF(count(*), 0) AS ota_pct,
                       max(COALESCE(ota_window_minutes, :window)) AS window_minutes,
                       max(COALESCE(ota_target, :defaultTarget))  AS target,
                       max(COALESCE(tolerance_pct, :defaultTolerance)) AS tolerance_pct,
                       max(COALESCE(sla_name, 'none')) AS sla_name,
                       max(COALESCE(s_vendor, s_bu, s_product, s_shift, 'all trips'))
                           AS sla_scope
                FROM scored
                GROUP BY vendor, business_unit, %s
                HAVING count(*) >= :minTrips
                ORDER BY (100.0 * count(*) FILTER (
                           WHERE delay_minutes <= COALESCE(ota_window_minutes, :window)
                         ) / NULLIF(count(*),0))
                         - max(COALESCE(ota_target, :defaultTarget)) ASC
                """.formatted(extraCol, extraName,
                String.format(SlaPolicyService.SCOPE_MATCH, "t"),
                SlaPolicyService.SPECIFICITY_ORDER,
                extraName, extraName);

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to).addValue("bu", businessUnit)
                .addValue("window", defaultWindow).addValue("defaultTarget", 95.0)
                .addValue("defaultTolerance", SlaPolicy.DEFAULT_TOLERANCE_PCT)
                .addValue("minTrips", minTrips);

        return jdbc.query(sql, p, (rs, i) -> {
            double ota = Sql.round1(rs.getDouble("ota_pct"));
            double target = Sql.round1(rs.getDouble("target"));
            double tolerance = rs.getDouble("tolerance_pct");
            double vs = Sql.round1(ota - target);

            // Scored through SlaPolicy so the API and the UI can never disagree with the
            // domain object about what MET means. The earlier version graded here by hand
            // and had it backwards: it called any shortfall a BREACH, and called exceeding
            // the target by under a point AT_RISK.
            String status = new SlaPolicy(null, null, null, null, null, null,
                    rs.getInt("window_minutes"), target, tolerance, null, 0,
                    null, null, true, null, null).verdict(ota).name();

            return new ComplianceRow(
                    rs.getString("vendor"), rs.getString("business_unit"),
                    "product_type".equals(extraName) ? rs.getString(extraName) : null,
                    "shift_type".equals(extraName) ? rs.getString(extraName) : null,
                    "shift_band".equals(extraName) ? rs.getString(extraName) : null,
                    rs.getLong("trips"), rs.getLong("late_trips"), ota,
                    rs.getInt("window_minutes"), target, tolerance, vs, status,
                    rs.getString("sla_name"), rs.getString("sla_scope"));
        });
    }
}
