package com.routemind.api;

import com.routemind.chat.QueryPlanner.Filters;
import com.routemind.chat.SlicedMetricService;
import com.routemind.chat.SlicedMetricService.Sliced;
import com.routemind.rules.RuleSetProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Two questions the metric board could not answer, each now a first-class endpoint:
 *
 *   "How is OTA on the night shift?"            -> /api/slice/ota
 *   "Who is consistently bad, not just bad now?" -> /api/slice/repeat-offenders
 *
 * Both are deliberately narrow. The metric board answers "how are we doing"; these answer
 * "how are we doing HERE" and "who keeps doing this", which is what an operations manager
 * actually escalates on.
 */
@RestController
@RequestMapping("/slice")
@CrossOrigin
public class SliceController {

    private final SlicedMetricService sliced;
    private final NamedParameterJdbcTemplate jdbc;
    private final RuleSetProperties rules;

    public SliceController(SlicedMetricService sliced, NamedParameterJdbcTemplate jdbc,
                           RuleSetProperties rules) {
        this.sliced = sliced;
        this.jdbc = jdbc;
        this.rules = rules;
    }

    /**
     * OTA restricted to a slice. Every filter is optional; supplying none is an error
     * rather than a silent full-population answer, because "no filter" here almost always
     * means the caller expected one to apply.
     */
    @GetMapping("/ota")
    public ResponseEntity<?> otaSlice(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String shiftBand,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String office,
            @RequestParam(required = false) String businessUnit) {

        Filters f = new Filters(upper(shiftBand), upper(direction), upper(productType),
                blank(vendor), blank(office));
        if (!f.any()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "give at least one of shiftBand, direction, productType, vendor, office",
                    "hint", "for the unsliced figure use /api/metrics/ota"));
        }

        return sliced.otaForSlice(f, from, to, blank(businessUnit),
                        rules.getSla().getOtaWindowMinutes(),
                        rules.getTargets().getOrDefault("ota", 95.0))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "slice", f.label(),
                        "note", "no trips matched this slice in the window")));
    }

    /**
     * Vendors that miss the target in MOST of the weeks in the window, not just the worst
     * one. A vendor 3 points down for six weeks running is a contract conversation; a
     * vendor 6 points down once had a bad week — and the alert stream, which compares
     * against the prior period, cannot tell them apart.
     */
    @GetMapping("/repeat-offenders")
    public List<Map<String, Object>> repeatOffenders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "vendor") String groupBy,
            @RequestParam(defaultValue = "200") int minTripsPerWeek) {

        // Only these two are substitutable into SQL, and only from this fixed set.
        String dim = switch (groupBy) {
            case "office" -> "office";
            case "product_type" -> "product_type";
            default -> "vendor";
        };
        double target = rules.getTargets().getOrDefault("ota", 95.0);

        return jdbc.queryForList("""
                WITH weekly AS (
                    SELECT t.%1$s AS member,
                           date_trunc('week', t.trip_date) AS wk,
                           count(*) AS trips,
                           100.0 * count(*) FILTER (WHERE t.delay_minutes <= :window)
                                 / NULLIF(count(*), 0) AS ota
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.product_type <> 'SPOT_2.0'
                    GROUP BY 1, 2
                    HAVING count(*) >= :minTrips
                )
                SELECT member,
                       count(*)                                        AS weeks_measured,
                       count(*) FILTER (WHERE ota < :target)           AS weeks_missed,
                       round(100.0 * count(*) FILTER (WHERE ota < :target)
                             / NULLIF(count(*), 0), 0)                 AS miss_rate_pct,
                       round(avg(ota)::numeric, 1)                     AS avg_ota,
                       round(min(ota)::numeric, 1)                     AS worst_week,
                       sum(trips)                                      AS trips
                FROM weekly
                GROUP BY member
                HAVING count(*) >= 3
                   AND count(*) FILTER (WHERE ota < :target) * 3 >= count(*)
                ORDER BY weeks_missed DESC, avg_ota ASC
                """.formatted(dim), new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("bu", blank(businessUnit))
                .addValue("window", rules.getSla().getOtaWindowMinutes())
                .addValue("target", target)
                .addValue("minTrips", minTripsPerWeek));
    }

    /**
     * Which vendors are actually carrying penalties, and how concentrated it is.
     *
     * Penalties live on `billing` as {@code is_penalty} lines priced in {@code trip_cost},
     * dated by BILLING CYCLE rather than trip date — a penalty for July can be raised in
     * August. Filtering these by trip_date, as the trip-shaped endpoints do, silently finds
     * almost nothing, so this endpoint exists rather than a filter on an existing one.
     *
     * Amounts are stored negative (a credit against the invoice); they are returned as
     * positive magnitudes here because "who has more penalty" is a question about size.
     */
    @GetMapping("/penalties")
    public Map<String, Object> penalties(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "10") int limit) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH pen AS (
                    SELECT vendor,
                           count(*)                    AS penalty_lines,
                           abs(sum(trip_cost))         AS penalty_amount
                    FROM billing
                    WHERE is_penalty
                      AND (CAST(:from AS date) IS NULL OR cycle_start >= :from)
                      AND (CAST(:to   AS date) IS NULL OR cycle_end   <= :to)
                      AND (CAST(:bu AS text)   IS NULL OR business_unit = :bu)
                    GROUP BY vendor
                )
                SELECT vendor, penalty_lines,
                       round(penalty_amount, 0) AS penalty_amount,
                       round(100.0 * penalty_amount
                             / NULLIF(sum(penalty_amount) OVER (), 0), 1) AS share_pct
                FROM pen
                ORDER BY penalty_amount DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("bu", blank(businessUnit))
                .addValue("limit", limit));

        double total = rows.stream()
                .mapToDouble(r -> ((Number) r.get("penalty_amount")).doubleValue()).sum();

        return Map.of(
                "vendors", rows,
                "totalShown", Math.round(total),
                // Concentration is the actual finding: an average hides that one contract
                // carries nearly all of it.
                "topShare", rows.isEmpty() ? 0 : rows.get(0).get("share_pct"),
                "note", rows.isEmpty()
                        ? "No penalty lines in this window."
                        : rows.get(0).get("vendor") + " carries "
                          + rows.get(0).get("share_pct") + "% of all penalties raised.");
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.toUpperCase(java.util.Locale.ROOT);
    }
}
