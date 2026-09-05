package com.routemind.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replays a historical day as if it were happening now, so the entire live path —
 * ingestion → fusion → in-flight alerting → SSE → dashboard — can be DEMONSTRATED
 * rather than described, without a real GPS feed.
 *
 * HONESTY: the dataset has no coordinates. We synthesise a straight-line path whose
 * LENGTH matches the trip's planned_km, and advance along it at a speed derived from the
 * trip's real recorded duration. So the *progress and timing are real*; the geography is
 * fabricated. Trips that were genuinely late in the data will be predicted late here —
 * which is exactly what makes the demo honest and repeatable.
 */
@Service
public class ReplaySimulator {

    public record ReplayStatus(boolean running, LocalDate day, int tripsLoaded,
                               int pingsEmitted, int speedFactor) {}

    private static final Logger log = LoggerFactory.getLogger(ReplaySimulator.class);
    private static final double LAT0 = 12.9716, LON0 = 77.5946;   // arbitrary origin
    private static final int TICK_MS = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final LivePositionStore store;
    private final LiveEtaService eta;
    private final LiveAlertService alerts;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger pings = new AtomicInteger();
    private volatile ScheduledExecutorService exec;
    private volatile LocalDate currentDay;
    private volatile int speedFactor = 60;
    private final List<ReplayTrip> trips = Collections.synchronizedList(new ArrayList<>());

    /** One trip being replayed. */
    private static final class ReplayTrip {
        long tripId;
        double plannedKm;
        double durationMin;      // real recorded duration -> implies real average speed
        double elapsedMin;
        boolean done;
    }

    public ReplaySimulator(NamedParameterJdbcTemplate jdbc, LivePositionStore store,
                           LiveEtaService eta, LiveAlertService alerts) {
        this.jdbc = jdbc;
        this.store = store;
        this.eta = eta;
        this.alerts = alerts;
    }

    /**
     * @param day     historical day to replay
     * @param speed   time compression (60 = one simulated minute per real second)
     * @param limit   how many trips to replay
     */
    public synchronized ReplayStatus start(LocalDate day, int speed, int limit) {
        stop();
        currentDay = day;
        speedFactor = Math.max(1, speed);
        pings.set(0);
        trips.clear();
        trips.addAll(load(day, limit));

        if (trips.isEmpty()) {
            log.warn("Replay: no trips found for {}", day);
            return status();
        }

        running.set(true);
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replay-sim");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("Replay started for {} — {} trips at {}x", day, trips.size(), speedFactor);
        return status();
    }

    public synchronized ReplayStatus stop() {
        running.set(false);
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
        return status();
    }

    /** One simulated minute-block per tick. */
    private void tick() {
        if (!running.get()) return;
        double minutesPerTick = speedFactor * (TICK_MS / 1000.0) / 60.0 * 60.0;
        Instant now = Instant.now();
        int active = 0;

        synchronized (trips) {
            for (ReplayTrip t : trips) {
                if (t.done) continue;
                active++;
                t.elapsedMin += minutesPerTick;

                double progress = t.durationMin <= 0 ? 1.0
                        : Math.min(t.elapsedMin / t.durationMin, 1.0);
                double coveredKm = t.plannedKm * progress;
                double speedKph = t.durationMin <= 0 ? 25.0
                        : (t.plannedKm / (t.durationMin / 60.0));

                // synthesise a position that is `coveredKm` along a straight line
                double dLat = coveredKm / 111.0;                 // ~111 km per degree lat
                store.accept(new GpsEvent(t.tripId, LAT0 + dLat, LON0,
                        speedKph, 0.0, now));
                pings.incrementAndGet();

                eta.predict(t.tripId, now).ifPresent(alerts::consider);

                if (progress >= 1.0) t.done = true;
            }
        }
        if (active == 0) {
            log.info("Replay complete for {} — {} pings emitted", currentDay, pings.get());
            stop();
        }
    }

    private List<ReplayTrip> load(LocalDate day, int limit) {
        String sql = """
                SELECT trip_id,
                       coalesce(planned_km, 10) AS planned_km,
                       CASE WHEN actual_start IS NOT NULL AND actual_end IS NOT NULL
                            THEN EXTRACT(EPOCH FROM (actual_end - actual_start))/60
                            ELSE 30 END AS duration_min
                FROM trips
                WHERE trip_date = :day
                  AND planned_km > 0.5
                ORDER BY delay_minutes DESC NULLS LAST
                LIMIT :limit
                """;
        try {
            return jdbc.query(sql, new MapSqlParameterSource()
                    .addValue("day", day).addValue("limit", limit), (rs, i) -> {
                ReplayTrip t = new ReplayTrip();
                t.tripId = rs.getLong("trip_id");
                t.plannedKm = rs.getDouble("planned_km");
                t.durationMin = Math.max(rs.getDouble("duration_min"), 1);
                return t;
            });
        } catch (Exception e) {
            log.warn("Replay load failed: {}", e.getMessage());
            return List.of();
        }
    }

    public ReplayStatus status() {
        return new ReplayStatus(running.get(), currentDay, trips.size(),
                pings.get(), speedFactor);
    }
}
