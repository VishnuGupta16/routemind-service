package com.routemind.diagnose;

import com.routemind.metrics.spi.Sql;
import com.routemind.sla.SlaPolicyService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Why is OTA down? — a deterministic decomposition, not a guess.
 *
 * The question "why did on-time arrival fall?" has a real, arithmetic answer, and this
 * computes it rather than narrating an impression. It compares a period against the
 * preceding one and attributes the change in the ON-TIME RATE to each value of a dimension,
 * using a shift-share decomposition:
 *
 *     contribution(group g, points) = -100 x ( lateRate_now(g) - lateRate_prev(g) )
 *                                            x  volumeShare_now(g)
 *
 * A negative contribution means that group pulled OTA down. The contributions across all
 * groups of a dimension sum (up to a small volume-mix residual) to the total OTA change, so
 * "morning pickups cost you 1.4 of the 2.0 points" is a statement that can be checked, not
 * an opinion. Every driver it returns carries the SQL that produced it.
 *
 * This is the RULE-BASED half of the dual-track answer. The AI half narrates over exactly
 * these numbers and never computes its own — see {@code OtaDiagnosisService}.
 *
 * On-time is scored per trip against that trip's own SLA window, the same as everywhere
 * else, so a diagnosis and a scorecard can never disagree about which trips were late.
 */
@Service
public class OtaRootCauseService {

    /** Rentals have no on-time commitment, so they must not appear in an OTA diagnosis. */
    private static final String EXCLUDE_RENTAL = " AND t.product_type <> 'SPOT_2.0' ";

    /** A group too small to matter should not surface as a "cause". */
    private static final int MIN_GROUP_TRIPS = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public OtaRootCauseService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One dimension value and how much of the OTA change it accounts for. */
    public record Driver(String dimension,
                         String value,
                         double otaNow,
                         double otaPrev,
                         double otaChange,        // points, this group alone
                         long tripsNow,
                         long lateNow,
                         long lateAdded,          // extra late trips vs the prior period
                         double contributionPts,  // share of the TOTAL OTA change it explains
                         String evidenceSql) {}

    /** The full diagnosis for one period against the one before it. */
    public record Diagnosis(LocalDate periodStart, LocalDate periodEnd,
                            LocalDate priorStart, LocalDate priorEnd,
                            String businessUnit,
                            double otaNow, double otaPrev, double otaChange,
                            long tripsNow, long tripsPrev,
                            boolean declined,
                            List<Driver> byDirection,
                            List<Driver> byShiftBand,
                            List<Driver> byProductType,
                            List<Driver> byOffice,
                            List<Driver> byVendor,
                            List<ReasonShift> reasonMix,
                            List<String> headlines) {}

    /** How the recorded CAUSE of lateness shifted — is more of it controllable now? */
    public record ReasonShift(String reason, double sharePrev, double shareNow,
                              double changePts, boolean controllable) {}

    /**
     * Diagnose the OTA change for [from,to] against the immediately preceding window of the
     * same length.
     */
    public Diagnosis diagnose(LocalDate from, LocalDate to, String businessUnit,
                              int defaultWindow) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(days - 1);

        double otaNow = periodOta(from, to, businessUnit, defaultWindow);
        double otaPrev = periodOta(priorFrom, priorTo, businessUnit, defaultWindow);
        long nNow = periodCount(from, to, businessUnit);
        long nPrev = periodCount(priorFrom, priorTo, businessUnit);
        double change = round(otaNow - otaPrev);

        List<Driver> dir = drivers("trip_direction", from, to, priorFrom, priorTo,
                businessUnit, defaultWindow);
        List<Driver> band = drivers("shift_band", from, to, priorFrom, priorTo,
                businessUnit, defaultWindow);
        List<Driver> prod = drivers("product_type", from, to, priorFrom, priorTo,
                businessUnit, defaultWindow);
        List<Driver> office = drivers("office", from, to, priorFrom, priorTo,
                businessUnit, defaultWindow);
        List<Driver> vendor = drivers("vendor", from, to, priorFrom, priorTo,
                businessUnit, defaultWindow);
        List<ReasonShift> reasons = reasonMix(from, to, priorFrom, priorTo, businessUnit,
                defaultWindow);

        List<String> headlines = headlines(change, dir, band, office, vendor, reasons);

        return new Diagnosis(from, to, priorFrom, priorTo, businessUnit,
                otaNow, otaPrev, change, nNow, nPrev, change < 0,
                dir, band, prod, office, vendor, reasons, headlines);
    }

    // ------------------------------------------------------------- the decomposition

    private List<Driver> drivers(String dim, LocalDate from, LocalDate to,
                                 LocalDate priorFrom, LocalDate priorTo,
                                 String bu, int window) {
        String win = SlaPolicyService.windowForTrip("t");
        // now vs prior, per group, in a single scan of each window
        String sql = ("""
                WITH now AS (
                    SELECT t.%1$s AS grp,
                           count(*) AS trips,
                           count(*) FILTER (WHERE t.delay_minutes > %2$s) AS late
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (:bu IS NULL OR t.business_unit = :bu)
                """ + EXCLUDE_RENTAL + """
                    GROUP BY t.%1$s
                ),
                prev AS (
                    SELECT t.%1$s AS grp,
                           count(*) AS trips,
                           count(*) FILTER (WHERE t.delay_minutes > %2$s) AS late
                    FROM trips t
                    WHERE t.trip_date BETWEEN :priorFrom AND :priorTo
                      AND (:bu IS NULL OR t.business_unit = :bu)
                """ + EXCLUDE_RENTAL + """
                    GROUP BY t.%1$s
                ),
                total_now AS (SELECT sum(trips) n FROM now)
                SELECT n.grp,
                       n.trips AS trips_now,
                       n.late  AS late_now,
                       100.0 * (1 - n.late::numeric / NULLIF(n.trips,0)) AS ota_now,
                       100.0 * (1 - COALESCE(p.late,0)::numeric
                                     / NULLIF(p.trips,0)) AS ota_prev,
                       n.late - round(COALESCE(p.late,0)::numeric
                                      / NULLIF(p.trips,0) * n.trips) AS late_added,
                       -100.0 * ( n.late::numeric / NULLIF(n.trips,0)
                                  - COALESCE(p.late,0)::numeric / NULLIF(p.trips,0) )
                              * ( n.trips::numeric / NULLIF((SELECT n FROM total_now),0) )
                              AS contribution
                FROM now n LEFT JOIN prev p ON p.grp = n.grp
                WHERE n.trips >= %3$d
                ORDER BY contribution ASC
                """).formatted(dim, win, MIN_GROUP_TRIPS);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("priorFrom", priorFrom).addValue("priorTo", priorTo)
                .addValue("bu", bu).addValue("window", window);

        List<Driver> out = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            out.add(new Driver(dim, rs.getString("grp"),
                    Sql.round1(rs.getDouble("ota_now")),
                    Sql.round1(rs.getDouble("ota_prev")),
                    Sql.round1(rs.getDouble("ota_now") - rs.getDouble("ota_prev")),
                    rs.getLong("trips_now"), rs.getLong("late_now"),
                    rs.getLong("late_added"),
                    Sql.round2(rs.getDouble("contribution")),
                    "-- " + dim + " decomposition, this row re-derives its own numbers\n" + sql));
        });
        // strongest downward driver first; a positive contribution helped, keep it last
        return out;
    }

    /**
     * Did the CAUSE mix move toward controllable delay?
     *
     * A drop where DRIVER lateness grew is a vendor-management problem; the same drop driven
     * by TRAFFIC is not the vendor's fault. The facilities head needs to know which, because
     * only one of them is a contract conversation.
     */
    private List<ReasonShift> reasonMix(LocalDate from, LocalDate to,
                                        LocalDate priorFrom, LocalDate priorTo,
                                        String bu, int window) {
        String win = SlaPolicyService.windowForTrip("t");
        String sql = ("""
                WITH late_now AS (
                    SELECT t.delay_reason AS reason, count(*) AS c
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (:bu IS NULL OR t.business_unit = :bu)
                      AND t.delay_minutes > %1$s
                """ + EXCLUDE_RENTAL + """
                    GROUP BY t.delay_reason
                ),
                late_prev AS (
                    SELECT t.delay_reason AS reason, count(*) AS c
                    FROM trips t
                    WHERE t.trip_date BETWEEN :priorFrom AND :priorTo
                      AND (:bu IS NULL OR t.business_unit = :bu)
                      AND t.delay_minutes > %1$s
                """ + EXCLUDE_RENTAL + """
                    GROUP BY t.delay_reason
                )
                SELECT COALESCE(n.reason, p.reason) AS reason,
                       100.0 * COALESCE(p.c,0) / NULLIF((SELECT sum(c) FROM late_prev),0) AS share_prev,
                       100.0 * COALESCE(n.c,0) / NULLIF((SELECT sum(c) FROM late_now),0)  AS share_now
                FROM late_now n FULL OUTER JOIN late_prev p ON p.reason = n.reason
                WHERE COALESCE(n.reason, p.reason) <> 'NODELAY'
                ORDER BY share_now DESC
                """).formatted(win);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("priorFrom", priorFrom).addValue("priorTo", priorTo)
                .addValue("bu", bu).addValue("window", window);

        List<ReasonShift> out = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            String reason = rs.getString("reason");
            double prev = Sql.round1(rs.getDouble("share_prev"));
            double now = Sql.round1(rs.getDouble("share_now"));
            out.add(new ReasonShift(reason, prev, now, Sql.round1(now - prev),
                    // DRIVER is the only cleanly vendor-controllable reason. TRAFFIC is
                    // not the vendor's fault; EMPLOYEE is the rider not coming down. Naming
                    // DRIVER as controllable is a claim, flagged in open-questions A4.
                    "DRIVER".equalsIgnoreCase(reason)));
        });
        return out;
    }

    // ------------------------------------------------------------- plain-English drivers

    /**
     * The two or three sentences a human would actually say. Deterministic and template-
     * driven — this is the rule-based narrative, and it states only what the decomposition
     * proved.
     */
    private List<String> headlines(double change, List<Driver> dir, List<Driver> band,
                                   List<Driver> office, List<Driver> vendor,
                                   List<ReasonShift> reasons) {
        List<String> out = new ArrayList<>();
        if (change >= 0) {
            out.add(String.format(
                    "On-time arrival did not fall — it moved %+.1f points versus the prior period.",
                    change));
            return out;
        }

        out.add(String.format("On-time arrival fell %.1f points versus the prior period.",
                Math.abs(change)));

        Driver d = strongest(dir);
        Driver b = strongest(band);
        if (d != null && b != null) {
            out.add(String.format(
                    "%s trips account for %.1f of those points; within them the %s shift band "
                            + "is the worst, down %.1f points to %.1f%% on %,d trips.",
                    label(d.value()), Math.abs(d.contributionPts()),
                    b.value().toLowerCase(), Math.abs(b.otaChange()), b.otaNow(), b.tripsNow()));
        }

        Driver o = strongest(office);
        if (o != null && Math.abs(o.contributionPts()) >= 0.2) {
            out.add(String.format(
                    "By location it is concentrated at %s: %.1f%% now versus %.1f%% before, "
                            + "%.1f of the points on its own.",
                    o.value(), o.otaNow(), o.otaPrev(), Math.abs(o.contributionPts())));
        }

        ReasonShift driver = reasons.stream()
                .filter(rs -> "DRIVER".equalsIgnoreCase(rs.reason())).findFirst().orElse(null);
        if (driver != null && driver.changePts() > 0.5) {
            out.add(String.format(
                    "The cause mix moved toward the vendor's control: driver-caused lateness "
                            + "rose from %.1f%% to %.1f%% of late trips — the part a vendor "
                            + "conversation can actually change. (Attribution of DRIVER as "
                            + "controllable is unconfirmed — see open question A4.)",
                    driver.sharePrev(), driver.shareNow()));
        } else {
            out.add("The cause mix did not move toward driver-controlled lateness, so this "
                    + "looks more like traffic or demand than vendor performance.");
        }
        return out;
    }

    private static Driver strongest(List<Driver> drivers) {
        return drivers.isEmpty() ? null
                : drivers.stream().filter(d -> d.contributionPts() < 0)
                .min((a, b) -> Double.compare(a.contributionPts(), b.contributionPts()))
                .orElse(null);
    }

    private static String label(String direction) {
        if ("LOGIN".equalsIgnoreCase(direction)) return "Morning pickup (LOGIN)";
        if ("LOGOUT".equalsIgnoreCase(direction)) return "Evening drop (LOGOUT)";
        return direction;
    }

    // ------------------------------------------------------------------ totals

    private double periodOta(LocalDate from, LocalDate to, String bu, int window) {
        String win = SlaPolicyService.windowForTrip("t");
        Double v = jdbc.queryForObject(("""
                SELECT 100.0 * (1 - count(*) FILTER (WHERE t.delay_minutes > %1$s)::numeric
                                    / NULLIF(count(*),0))
                FROM trips t
                WHERE t.trip_date BETWEEN :from AND :to
                  AND (:bu IS NULL OR t.business_unit = :bu)
                """ + EXCLUDE_RENTAL).formatted(win),
                new MapSqlParameterSource("from", from).addValue("to", to)
                        .addValue("bu", bu).addValue("window", window), Double.class);
        return v == null ? 0 : Sql.round2(v);
    }

    private long periodCount(LocalDate from, LocalDate to, String bu) {
        Long v = jdbc.queryForObject(("""
                SELECT count(*) FROM trips t
                WHERE t.trip_date BETWEEN :from AND :to
                  AND (:bu IS NULL OR t.business_unit = :bu)
                """ + EXCLUDE_RENTAL),
                new MapSqlParameterSource("from", from).addValue("to", to).addValue("bu", bu),
                Long.class);
        return v == null ? 0 : v;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
