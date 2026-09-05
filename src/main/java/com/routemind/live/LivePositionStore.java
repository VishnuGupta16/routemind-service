package com.routemind.live;

import com.routemind.live.Live.TripPosition;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state of every in-flight trip, fed by the Kafka GPS stream.
 *
 * Becomes the {@code @Primary} TripPositionProvider when the live feed is enabled —
 * the dormant GPS branch everywhere downstream lights up with no other change.
 *
 * Remaining distance is derived WITHOUT destination coordinates: we accumulate
 * ground truth covered from successive pings (haversine) and subtract from the
 * trip's planned_km, which the dataset already provides.
 */
@Component
@Primary
public class LivePositionStore implements TripPositionProvider {

    /** Rolling state for one trip. */
    public static final class TripState {
        public final long tripId;
        public volatile double lat, lon, lastSpeedKph;
        public volatile double coveredKm;
        public volatile Instant firstSeen, lastSeen;
        public volatile int samples;
        private final Deque<Double> recentSpeeds = new ArrayDeque<>();

        TripState(long tripId) { this.tripId = tripId; }

        /** Mean of the last few pings — smooths out stops at lights. */
        public synchronized double smoothedSpeedKph() {
            if (recentSpeeds.isEmpty()) return lastSpeedKph;
            return recentSpeeds.stream().mapToDouble(Double::doubleValue).average()
                    .orElse(lastSpeedKph);
        }

        synchronized void pushSpeed(double v) {
            recentSpeeds.addLast(v);
            while (recentSpeeds.size() > 6) recentSpeeds.removeFirst();
        }
    }

    private final Map<Long, TripState> states = new ConcurrentHashMap<>();

    /** Apply one ping. Returns the updated state. */
    public TripState accept(GpsEvent e) {
        TripState s = states.computeIfAbsent(e.tripId(), TripState::new);
        synchronized (s) {
            if (s.samples > 0) {
                double step = haversineKm(s.lat, s.lon, e.lat(), e.lon());
                // ignore GPS jitter and impossible jumps (bad fix / tunnel exit)
                if (step > 0.005 && step < 5.0) s.coveredKm += step;
            } else {
                s.firstSeen = e.observedAt();
            }
            s.lat = e.lat();
            s.lon = e.lon();
            s.lastSpeedKph = e.speedKph();
            s.pushSpeed(e.speedKph());
            s.lastSeen = e.observedAt();
            s.samples++;
        }
        return s;
    }

    @Override
    public Optional<TripPosition> positionOf(long tripId) {
        TripState s = states.get(tripId);
        if (s == null || s.samples == 0) return Optional.empty();
        return Optional.of(new TripPosition(tripId, s.lat, s.lon,
                s.smoothedSpeedKph(), Double.NaN, s.lastSeen));
    }

    public Optional<TripState> state(long tripId) { return Optional.ofNullable(states.get(tripId)); }

    public Collection<TripState> inFlight() { return states.values(); }

    /** Honest: "live" means data is actually flowing, not merely that the bean exists. */
    @Override public boolean live() { return !states.isEmpty(); }

    @Override public String name() {
        return states.isEmpty() ? "gps-store (idle — no feed)" : "gps-stream (active)";
    }

    /** Drop trips we haven't heard from in a while — they've completed or gone dark. */
    public int evictStale(Duration maxAge, Instant now) {
        int before = states.size();
        states.values().removeIf(s -> s.lastSeen == null
                || s.lastSeen.isBefore(now.minus(maxAge)));
        return before - states.size();
    }

    public Map<String, Object> stats() {
        return Map.of("tripsTracked", states.size(),
                "totalSamples", states.values().stream().mapToInt(s -> s.samples).sum());
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
