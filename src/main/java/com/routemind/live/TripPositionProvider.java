package com.routemind.live;

import com.routemind.live.Live.TripPosition;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * FUTURE PLUG POINT — live vehicle positions.
 *
 * The provided dataset has no GPS, so the default implementation reports
 * "unavailable" and the risk engine falls back to historical patterns. When a real
 * feed exists (vehicle tracker, MoveInSync device stream, Kafka topic), implement
 * this interface, mark it {@code @Primary}, and every downstream prediction
 * automatically becomes position-aware. Nothing else changes.
 */
public interface TripPositionProvider {

    /** Empty when no live position is known for this trip. */
    Optional<TripPosition> positionOf(long tripId);

    /** Whether a live feed is actually connected — surfaced in /api/live/status. */
    boolean live();

    default String name() { return getClass().getSimpleName(); }

    /** Default: no GPS feed. Honest about it rather than faking coordinates. */
    @Component
    class Unavailable implements TripPositionProvider {
        public Optional<TripPosition> positionOf(long tripId) { return Optional.empty(); }
        public boolean live() { return false; }
        public String name() { return "no-gps-feed"; }
    }
}
