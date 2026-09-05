package com.routemind.live;

import com.routemind.live.Live.TrafficFactor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * FUTURE PLUG POINT — live traffic.
 *
 * Swap in Google/Mapbox/TomTom by implementing this and marking it {@code @Primary}.
 * Until then we derive a factor from the dataset itself: the historical share of
 * trips delayed for reason TRAFFIC, by office and hour of day. That is real signal,
 * not a placeholder — it already tells you which corridors and hours run badly.
 */
public interface TrafficProvider {

    TrafficFactor factorFor(String office, LocalTime timeOfDay);

    boolean live();

    default String name() { return getClass().getSimpleName(); }

    /** Historical traffic profile computed from delay_reason = 'TRAFFIC'. */
    @Component
    class Historical implements TrafficProvider {

        private final NamedParameterJdbcTemplate jdbc;
        private final Map<String, Double> cache = new java.util.concurrent.ConcurrentHashMap<>();

        public Historical(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public TrafficFactor factorFor(String office, LocalTime t) {
            String key = office + "|" + t.getHour();
            double factor = cache.computeIfAbsent(key, k -> lookup(office, t.getHour()));
            return new TrafficFactor(office, factor, "historical");
        }

        private double lookup(String office, int hour) {
            String sql = """
                    SELECT coalesce(
                             1.0 + 2.0 * (count(*) FILTER (WHERE delay_reason = 'TRAFFIC')::numeric
                                          / NULLIF(count(*), 0)), 1.0) AS factor
                    FROM trips
                    WHERE office = :office
                      AND extract(hour FROM planned_start) = :hour
                    """;
            try {
                Double f = jdbc.queryForObject(sql,
                        Map.of("office", office == null ? "" : office, "hour", hour), Double.class);
                return f == null ? 1.0 : Math.min(f, 3.0);
            } catch (Exception e) {
                return 1.0;
            }
        }

        @Override public boolean live() { return false; }
        @Override public String name() { return "historical-traffic-profile"; }
    }
}
