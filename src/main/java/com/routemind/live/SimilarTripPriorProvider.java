package com.routemind.live;

import com.routemind.metrics.spi.Sql;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SIMILARITY SEARCH prior — k-nearest-neighbour over historical trips.
 *
 * Rather than averaging a coarse bucket, we score every candidate historical trip by
 * distance in a small normalised feature space (hour of day, day of week, distance,
 * planned duration, same office / vendor / shift / direction), take the k closest, and
 * predict from what ACTUALLY happened to them. The spread of those neighbours gives an
 * honest confidence rather than a guess.
 *
 * Implemented as an exact k-NN in SQL: at ~600k trips, filtered to one office, this is
 * a few milliseconds and needs no extra infrastructure. The scale-up path is to store
 * the same feature vector in a pgvector column and switch the ORDER BY to `<->`, which
 * turns it into an ANN index lookup — same maths, sub-linear.
 *
 * Enable with routemind.live.prior-strategy=SIMILAR
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "routemind.live", name = "prior-strategy",
        havingValue = "SIMILAR")
public class SimilarTripPriorProvider implements TripPriorProvider {

    private static final int K = 40;

    private final NamedParameterJdbcTemplate jdbc;
    private final com.routemind.rules.RuleSetProperties rules;

    public SimilarTripPriorProvider(NamedParameterJdbcTemplate jdbc,
                                    com.routemind.rules.RuleSetProperties rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    @Override
    public Prior priorFor(TripContext c) {
        // Weighted euclidean distance over normalised features. Categorical matches
        // (office/vendor/shift/direction) contribute a fixed penalty when they differ.
        String sql = """
                WITH neighbours AS (
                    SELECT
                        least(delay_minutes, :maxDelay) AS delay_minutes,
                        (delay_minutes > :window)::int  AS was_late,
                        sqrt(
                              pow((extract(hour FROM planned_start) - :hour) / 12.0, 2) * 2.0
                            + pow((extract(dow  FROM trip_date)    - :dow)  / 6.0,  2) * 1.0
                            + pow((coalesce(planned_km, 0)         - :km)   / 30.0, 2) * 1.5
                            + CASE WHEN office    = :office    THEN 0 ELSE 2.0 END
                            + CASE WHEN vendor    = :vendor    THEN 0 ELSE 1.5 END
                            + CASE WHEN shift_type = :shift    THEN 0 ELSE 1.0 END
                            + CASE WHEN trip_direction = :dir  THEN 0 ELSE 0.5 END
                        ) AS distance
                    FROM trips
                    WHERE trip_date < :date
                      AND delay_minutes IS NOT NULL
                      AND office = :office            -- hard filter keeps the scan small
                    ORDER BY distance ASC
                    LIMIT :k
                )
                SELECT count(*)                              AS n,
                       avg(delay_minutes)                    AS expected_delay,
                       avg(was_late) * 100.0                 AS late_pct,
                       coalesce(stddev_pop(delay_minutes),0) AS spread,
                       avg(distance)                         AS avg_distance
                FROM neighbours
                """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("hour", c.hourOfDay())
                .addValue("dow", c.dayOfWeek())
                .addValue("km", c.plannedKm())
                .addValue("office", c.office())
                .addValue("vendor", c.vendor())
                .addValue("shift", c.shift())
                .addValue("dir", c.direction())
                .addValue("date", c.date())
                .addValue("k", K)
                .addValue("window", rules.getSla().getOtaWindowMinutes())
                .addValue("maxDelay", rules.getSla().getMaxCredibleDelayMinutes());

        try {
            return jdbc.query(sql, p, rs -> {
                if (!rs.next()) return Prior.UNKNOWN;
                long n = rs.getLong("n");
                if (n == 0) return Prior.UNKNOWN;
                double expected = rs.getDouble("expected_delay");
                double latePct = rs.getDouble("late_pct");
                double spread = rs.getDouble("spread");
                double avgDist = rs.getDouble("avg_distance");

                // tight neighbours + low spread => trust it
                double confidence = Math.max(0.15, Math.min(0.95,
                        (1.0 / (1.0 + avgDist)) * (1.0 / (1.0 + spread / 10.0))));

                return new Prior(Sql.round1(expected), Sql.round1(latePct),
                        Sql.round2(confidence), n,
                        "k-NN over " + n + " similar trips (avg distance "
                                + Sql.round2(avgDist) + ", spread " + Sql.round1(spread) + " min)");
            });
        } catch (Exception e) {
            return Prior.UNKNOWN;
        }
    }

    @Override public String strategy() { return "SIMILAR (k-NN, k=" + K + ")"; }
}
