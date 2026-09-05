package com.routemind.live;

import java.time.Instant;

/** Value types for the live/predictive layer. */
public final class Live {

    private Live() {}

    /** Where a vehicle is right now. Populated by a real GPS feed when one exists. */
    public record TripPosition(long tripId,
                               double lat,
                               double lon,
                               double speedKph,
                               double remainingKm,
                               Instant observedAt) {}

    /** How bad traffic is on this corridor right now. 1.0 = free flow. */
    public record TrafficFactor(String area, double factor, String source) {}

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /**
     * A prediction about a single trip, made while it can still be fixed.
     * `basis` records HOW we knew — history, live GPS, or both.
     */
    public record TripRisk(long tripId,
                           String vendor,
                           String office,
                           String shift,
                           Instant scheduledStart,
                           int predictedDelayMin,
                           double confidence,
                           RiskLevel level,
                           int employeesAffected,
                           String basis,
                           String reason,
                           String recommendedAction) {}
}
