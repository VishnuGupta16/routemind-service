package com.routemind.live;

import com.routemind.live.Live.RiskLevel;
import com.routemind.live.Live.TripPosition;
import com.routemind.live.Live.TrafficFactor;
import com.routemind.live.Live.TripRisk;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Predicts which trips will fail — WHILE THEY CAN STILL BE FIXED.
 *
 * Works today with no GPS: the risk of a given (vendor × office × shift) is learned
 * from its own history, then adjusted by the traffic profile for that hour. When a
 * live GPS feed is plugged in, the same method refines the estimate with the real
 * remaining distance — the API and the consumers do not change.
 */
@Service
public class LiveRiskService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TripPositionProvider positions;
    private final TrafficProvider traffic;
    private final com.routemind.rules.RuleSetProperties rules;

    public LiveRiskService(NamedParameterJdbcTemplate jdbc,
                           TripPositionProvider positions,
                           TrafficProvider traffic,
                           com.routemind.rules.RuleSetProperties rules) {
        this.jdbc = jdbc;
        this.positions = positions;
        this.traffic = traffic;
        this.rules = rules;
    }

    /**
     * Risk for the trips scheduled on a given day — the "tomorrow morning" briefing.
     * Ordered worst first.
     */
    public List<TripRisk> riskFor(LocalDate day, String businessUnit, int limit) {
        // historical lateness for each vendor/office/shift combination, plus the
        // employees riding each upcoming trip
        String sql = """
                WITH history AS (
                    -- delay winsorised at :maxDelay: the dataset has artefacts up to
                    -- 10,644 minutes which would otherwise dominate this average
                    SELECT vendor, office, shift_type,
                           avg(least(delay_minutes, :maxDelay))                 AS avg_delay,
                           100.0 * count(*) FILTER (WHERE delay_minutes > :window)
                                 / NULLIF(count(*),0)                           AS late_pct,
                           count(*)                                             AS n
                    FROM trips
                    WHERE trip_date < :day
                    GROUP BY vendor, office, shift_type
                    HAVING count(*) >= 20
                ), upcoming AS (
                    SELECT t.trip_id, t.vendor, t.office, t.shift_type, t.planned_start,
                           coalesce(t.planned_employee_cnt, 0) AS emp
                    FROM trips t
                    WHERE t.trip_date = :day
                      AND (:bu IS NULL OR t.business_unit = :bu)
                )
                SELECT u.trip_id, u.vendor, u.office, u.shift_type, u.planned_start, u.emp,
                       h.avg_delay, h.late_pct, h.n
                FROM upcoming u
                JOIN history h ON h.vendor = u.vendor
                              AND h.office = u.office
                              AND h.shift_type = u.shift_type
                WHERE h.late_pct > 0
                ORDER BY h.late_pct DESC, u.emp DESC
                LIMIT :limit
                """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("day", day).addValue("bu", businessUnit).addValue("limit", limit)
                .addValue("window", rules.getSla().getOtaWindowMinutes())
                .addValue("maxDelay", rules.getSla().getMaxCredibleDelayMinutes());

        return jdbc.query(sql, p, (rs, i) -> {
            long tripId = rs.getLong("trip_id");
            String office = rs.getString("office");
            String shift = rs.getString("shift_type");
            double avgDelay = rs.getDouble("avg_delay");
            double latePct = rs.getDouble("late_pct");
            long n = rs.getLong("n");
            int emp = rs.getInt("emp");
            java.sql.Timestamp ts = rs.getTimestamp("planned_start");

            LocalTime tod = ts == null ? LocalTime.NOON : ts.toLocalDateTime().toLocalTime();
            TrafficFactor tf = traffic.factorFor(office, tod);

            // baseline prediction from history, scaled by the traffic profile
            double predicted = avgDelay * tf.factor();
            String basis = "history(" + n + " trips) × traffic(" + Sql.round2(tf.factor()) + ")";

            // if a live GPS feed exists, refine with the real remaining distance
            Optional<TripPosition> pos = positions.positionOf(tripId);
            if (pos.isPresent()) {
                TripPosition tp = pos.get();
                double speed = Math.max(tp.speedKph(), 5.0);
                double etaMin = (tp.remainingKm() / speed) * 60.0 * tf.factor();
                predicted = etaMin;
                basis = "live GPS " + Sql.round1(tp.remainingKm()) + "km @ "
                        + Sql.round1(tp.speedKph()) + "km/h × traffic";
            }

            int delay = (int) Math.round(predicted);
            double confidence = Math.min(0.5 + (n / 200.0), 0.95);
            RiskLevel level = delay >= 20 || latePct >= 25 ? RiskLevel.HIGH
                    : delay >= 10 || latePct >= 12 ? RiskLevel.MEDIUM : RiskLevel.LOW;

            String reason = String.format(
                    "%s on %s %s is late %.0f%% of the time (avg %.0f min); traffic factor %.2f",
                    rs.getString("vendor"), office, shift, latePct, avgDelay, tf.factor());

            String action = level == RiskLevel.HIGH
                    ? "Pre-assign a backup vehicle and notify the floor manager for shift " + shift
                    : "Monitor; confirm driver assignment 30 minutes before pickup";

            return new TripRisk(tripId, rs.getString("vendor"), office, shift,
                    ts == null ? null : ts.toInstant(), delay, Sql.round2(confidence),
                    level, emp, basis, reason, action);
        });
    }

    /** What the predictive layer is currently running on. */
    public java.util.Map<String, Object> status() {
        return java.util.Map.of(
                "positionProvider", positions.name(),
                "gpsLive", positions.live(),
                "trafficProvider", traffic.name(),
                "trafficLive", traffic.live(),
                "mode", positions.live() ? "LIVE_GPS" : "HISTORICAL_PATTERN");
    }
}
