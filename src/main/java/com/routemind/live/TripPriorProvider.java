package com.routemind.live;

import java.time.LocalDate;

/**
 * The HISTORICAL half of the prediction: what usually happens to a trip like this one.
 *
 * Two strategies ship, selected by {@code routemind.live.prior-strategy}:
 *   KEYED   — average behaviour of the (vendor × office × shift) bucket. Cheap, robust.
 *   SIMILAR — k-nearest-neighbour over a feature vector of the trip's CURRENT state,
 *             so the prior conditions on how today is actually going, not just static keys.
 */
public interface TripPriorProvider {

    /** What we know about the trip when we ask for a prior. */
    record TripContext(long tripId,
                       String vendor,
                       String office,
                       String shift,
                       String direction,
                       LocalDate date,
                       int hourOfDay,
                       int dayOfWeek,
                       double plannedKm,
                       double plannedDurationMin,
                       double progressFraction,     // 0..1 covered/planned
                       double elapsedMin,
                       double observedSpeedKph) {}

    /** Expected final delay in minutes, with a confidence and how we got there. */
    record Prior(double expectedDelayMin,
                 double lateProbability,
                 double confidence,
                 long sampleSize,
                 String basis) {
        public static final Prior UNKNOWN = new Prior(0, 0, 0.1, 0, "no-history");
    }

    Prior priorFor(TripContext ctx);

    String strategy();
}
