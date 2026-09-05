package com.routemind.persona;

import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * The LINE MANAGER lens — shift-level, not period-level.
 * "Who made it, who was late, and how it hits floor readiness."
 */
@Service
public class ShiftReadinessService {

    public record ShiftRow(String shift,
                           String office,
                           long trips,
                           long lateTrips,
                           double otaPct,
                           long employeesExpected,
                           long employeesBoarded,
                           long noShows,
                           double readinessPct,
                           String note) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ShiftReadinessService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Per-shift readiness for one day. */
    public List<ShiftRow> forDay(LocalDate day, String businessUnit, int window) {
        String sql = """
                WITH t AS (
                    SELECT shift_type, office, trip_id,
                           (delay_minutes > :window) AS late
                    FROM trips
                    WHERE trip_date = :day
                      AND (CAST(:bu AS text) IS NULL OR business_unit = :bu)
                ), e AS (
                    SELECT shift_type, office,
                           count(*)                                  AS expected,
                           count(*) FILTER (WHERE boarding_status = 'Boarded') AS boarded,
                           count(*) FILTER (WHERE is_no_show)        AS no_shows
                    FROM trip_employees
                    WHERE trip_date = :day
                      AND (CAST(:bu AS text) IS NULL OR business_unit = :bu)
                    GROUP BY shift_type, office
                )
                SELECT t.shift_type, t.office,
                       count(*)                            AS trips,
                       count(*) FILTER (WHERE t.late)      AS late_trips,
                       100.0 * count(*) FILTER (WHERE NOT t.late) / NULLIF(count(*),0) AS ota,
                       coalesce(max(e.expected), 0)        AS expected,
                       coalesce(max(e.boarded), 0)         AS boarded,
                       coalesce(max(e.no_shows), 0)        AS no_shows
                FROM t LEFT JOIN e
                       ON e.shift_type = t.shift_type AND e.office = t.office
                GROUP BY t.shift_type, t.office
                HAVING count(*) > 0
                ORDER BY ota ASC, trips DESC
                LIMIT 25
                """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("day", day).addValue("bu", businessUnit).addValue("window", window);

        return jdbc.query(sql, p, (rs, i) -> {
            long trips = rs.getLong("trips");
            long late = rs.getLong("late_trips");
            long expected = rs.getLong("expected");
            long boarded = rs.getLong("boarded");
            long noShows = rs.getLong("no_shows");
            double ota = Sql.round1(rs.getDouble("ota"));
            double readiness = expected == 0 ? 100.0
                    : Sql.round1(100.0 * boarded / expected);
            String note = note(ota, late, noShows, readiness);
            return new ShiftRow(rs.getString("shift_type"), rs.getString("office"),
                    trips, late, ota, expected, boarded, noShows, readiness, note);
        });
    }

    /** One employee on a shift — the line manager's real question: *who*. */
    public record EmployeeStatus(String employeeId,
                                 long tripId,
                                 String office,
                                 String shift,
                                 String boardingStatus,
                                 boolean noShow,
                                 Integer minutesLate,
                                 String note) {}

    /**
     * Per-employee detail for one shift. This is what a line manager actually acts on —
     * not "84% readiness" but "these six people are not here yet".
     */
    public List<EmployeeStatus> employeesForShift(LocalDate day, String shift, String office,
                                                  String businessUnit, int limit) {
        String sql = """
                SELECT e.stwid, e.trip_id, e.office, e.shift_type,
                       e.boarding_status, e.is_no_show,
                       CASE WHEN e.actual_pickup IS NOT NULL AND e.planned_pickup IS NOT NULL
                            THEN EXTRACT(EPOCH FROM (e.actual_pickup - e.planned_pickup)) / 60
                       END AS minutes_late
                FROM trip_employees e
                WHERE e.trip_date = :day
                  AND (CAST(:shift AS text)  IS NULL OR e.shift_type = :shift)
                  AND (CAST(:office AS text) IS NULL OR e.office = :office)
                  AND (CAST(:bu AS text)     IS NULL OR e.business_unit = :bu)
                  AND (e.is_no_show
                       OR e.boarding_status <> 'Boarded'
                       OR e.actual_pickup IS NULL
                       OR e.actual_pickup > e.planned_pickup + interval '10 minutes')
                ORDER BY e.is_no_show DESC, minutes_late DESC NULLS LAST
                LIMIT :limit
                """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("day", day).addValue("shift", shift).addValue("office", office)
                .addValue("bu", businessUnit).addValue("limit", limit);

        return jdbc.query(sql, p, (rs, i) -> {
            Object idObj = rs.getObject("stwid");
            String empId = idObj == null ? "unknown" : String.valueOf(idObj);
            boolean noShow = rs.getBoolean("is_no_show");
            Object lateObj = rs.getObject("minutes_late");
            Integer late = lateObj == null ? null : (int) Math.round(((Number) lateObj).doubleValue());
            String status = rs.getString("boarding_status");

            String note;
            if (noShow) note = "No-show — seat held and unused.";
            else if (!"Boarded".equals(status)) note = "Did not board (" + status + ").";
            else if (late == null) note = "No pickup recorded — status unknown.";
            else note = "Picked up " + late + " min late.";

            return new EmployeeStatus(empId, rs.getLong("trip_id"), rs.getString("office"),
                    rs.getString("shift_type"), status, noShow, late, note);
        });
    }

    private String note(double ota, long late, long noShows, double readiness) {
        if (readiness < 85) return "Floor short — " + Math.round(100 - readiness)
                + "% of expected staff did not board.";
        if (ota < 90) return late + " late trips on this shift — expect a staggered start.";
        if (noShows > 0) return noShows + " no-shows; seats were paid for but unused.";
        return "On track.";
    }
}
