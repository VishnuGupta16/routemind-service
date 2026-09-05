package com.routemind.chat;

import com.routemind.chat.QueryPlanner.Filters;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Answers "how is OTA on the night shift?" — a metric restricted to a slice the question
 * named.
 *
 * The registered metrics deliberately do not take a shift/direction/product filter: they
 * are the headline numbers, and adding optional predicates to all nine would make every
 * one of them harder to read for a case only OTA really needs. So a sliced question is
 * answered here, by one parameterised query that mirrors {@code OtaMetric} exactly — same
 * SLA window, same rental exclusion — so a slice and the headline can never be computed on
 * different bases.
 *
 * This is deterministic, not model-written: the filter came from the question, so the rows
 * measured are reproducible and the model is never asked to author this SQL.
 */
@Component
public class SlicedMetricService {

    private final NamedParameterJdbcTemplate jdbc;

    public SlicedMetricService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Sliced(String metricId, String sliceLabel,
                         double value, long sampleSize,
                         Double priorValue, Double target,
                         String verdict, String headline, String sql) {}

    /**
     * OTA for a slice, with the same window compared against the one before it.
     * Empty when the slice matched no trips — a slice with no data is not a zero.
     */
    public Optional<Sliced> otaForSlice(Filters f, LocalDate from, LocalDate to,
                                        String businessUnit, int window, double target) {
        if (!f.any()) return Optional.empty();

        String sql = """
                SELECT count(*) AS trips,
                       100.0 * count(*) FILTER (WHERE t.delay_minutes <= :window)
                             / NULLIF(count(*), 0) AS ota
                FROM trips t
                WHERE t.trip_date BETWEEN :from AND :to
                  AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                  AND t.product_type <> 'SPOT_2.0'
                  AND (CAST(:band AS text)    IS NULL OR t.shift_band     = :band)
                  AND (CAST(:dir AS text)     IS NULL OR t.trip_direction = :dir)
                  AND (CAST(:product AS text) IS NULL OR t.product_type   = :product)
                  AND (CAST(:vendor AS text)  IS NULL OR t.vendor         = :vendor)
                  AND (CAST(:office AS text)  IS NULL OR t.office         = :office)
                """;

        Map<String, Object> now = one(sql, f, from, to, businessUnit, window);
        long trips = num(now.get("trips")).longValue();
        if (trips == 0) return Optional.empty();
        double value = round(num(now.get("ota")).doubleValue());

        // the immediately preceding window of the same length
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        Map<String, Object> before = one(sql, f, priorTo.minusDays(days - 1), priorTo,
                businessUnit, window);
        Double prior = num(before.get("trips")).longValue() == 0 ? null
                : round(num(before.get("ota")).doubleValue());

        String verdict = value >= target ? "MET"
                : value >= target - 2.0 ? "AT_RISK" : "BREACH";

        String headline = "On-time arrival for %s is %.1f%% across %,d trips, against a target of %.1f%%%s. %s"
                .formatted(f.label(), value, trips, target,
                        prior == null ? "" : " (%s from %.1f%% last period)"
                                .formatted(value >= prior ? "up" : "down", prior),
                        switch (verdict) {
                            case "MET" -> "Within target.";
                            case "AT_RISK" -> "Close to target — worth watching.";
                            default -> "Below target.";
                        });

        return Optional.of(new Sliced("ota", f.label(), value, trips, prior, target,
                verdict, headline, sql.strip()));
    }

    private Map<String, Object> one(String sql, Filters f, LocalDate from, LocalDate to,
                                    String bu, int window) {
        return jdbc.queryForMap(sql, new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("bu", bu).addValue("window", window)
                .addValue("band", f.shiftBand())
                .addValue("dir", f.direction())
                .addValue("product", f.productType())
                .addValue("vendor", f.vendor())
                .addValue("office", f.office()));
    }

    private static Number num(Object o) { return o instanceof Number n ? n : 0; }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
}
