package com.routemind.live;

import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KEYED prior (the default) — average behaviour of the vendor × office × shift bucket.
 * Cheap, cached, and robust. Used unless prior-strategy=SIMILAR selects the k-NN one.
 */
@Component
public class KeyedTripPriorProvider implements TripPriorProvider {

    private final NamedParameterJdbcTemplate jdbc;
    private final com.routemind.rules.RuleSetProperties rules;
    private final Map<String, Prior> cache = new ConcurrentHashMap<>();

    public KeyedTripPriorProvider(NamedParameterJdbcTemplate jdbc,
                                  com.routemind.rules.RuleSetProperties rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    @Override
    public Prior priorFor(TripContext c) {
        String key = c.vendor() + "|" + c.office() + "|" + c.shift();
        return cache.computeIfAbsent(key, k -> load(c));
    }

    private Prior load(TripContext c) {
        // delay is winsorised at :maxDelay so a 10,644-minute artefact cannot
        // dominate the average this prediction is built on
        String sql = """
                SELECT count(*)                                        AS n,
                       avg(least(delay_minutes, :maxDelay))            AS expected_delay,
                       100.0 * count(*) FILTER (WHERE delay_minutes > :window)
                             / NULLIF(count(*),0)                      AS late_pct
                FROM trips
                WHERE vendor = :vendor AND office = :office AND shift_type = :shift
                  AND trip_date < :date
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("vendor", c.vendor()).addValue("office", c.office())
                .addValue("shift", c.shift()).addValue("date", c.date())
                .addValue("window", rules.getSla().getOtaWindowMinutes())
                .addValue("maxDelay", rules.getSla().getMaxCredibleDelayMinutes());
        try {
            return jdbc.query(sql, p, rs -> {
                if (!rs.next()) return Prior.UNKNOWN;
                long n = rs.getLong("n");
                if (n < 5) return Prior.UNKNOWN;
                double confidence = Math.min(0.9, 0.4 + n / 400.0);
                return new Prior(Sql.round1(rs.getDouble("expected_delay")),
                        Sql.round1(rs.getDouble("late_pct")),
                        Sql.round2(confidence), n,
                        "vendor×office×shift history (" + n + " trips)");
            });
        } catch (Exception e) {
            return Prior.UNKNOWN;
        }
    }

    @Override public String strategy() { return "KEYED (vendor×office×shift)"; }
}
