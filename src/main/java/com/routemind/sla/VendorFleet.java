package com.routemind.sla;

import java.time.LocalDate;

/**
 * One vendor × cab type × shift band combination that is actually operating.
 *
 * This is what an SLA gets configured against. Without it the onboarding screen is a set of
 * free-text boxes, and nothing stops someone committing a vendor to 97% on a cab type they
 * have never run — a target that can never be measured, on a scorecard that quietly reads
 * "no data" forever. Picking from real combinations, with the observed rate visible while
 * you choose the target, makes the commitment informed rather than invented.
 *
 * Derived from the trip data, never entered by hand.
 */
public record VendorFleet(
        Long id,
        String businessUnit,
        String vendor,
        String productType,
        String shiftBand,
        long trips,
        int vehicles,
        LocalDate firstSeen,
        LocalDate lastSeen,
        Double observedOta,      // at the default 10-minute window, for reference
        Double avgDelayMinutes,
        // filled in by the service, not by the table
        SlaPolicy appliedSla,
        SlaPolicy.Verdict verdict) {

    /**
     * Below this, a combination is real but too small to judge — a 12-trip pairing hitting
     * 100% or 50% tells you nothing, and putting it on a scorecard invites a bad decision.
     * It stays visible and configurable; it just is not scored.
     */
    public static final int MIN_TRIPS_TO_JUDGE = 100;

    public boolean judgeable() { return trips >= MIN_TRIPS_TO_JUDGE; }

    public String label() {
        return vendor + " · " + productType + " · " + shiftBand.toLowerCase();
    }

    public VendorFleet withSla(SlaPolicy sla) {
        SlaPolicy.Verdict v = (sla == null || observedOta == null || !judgeable())
                ? null : sla.verdict(observedOta);
        return new VendorFleet(id, businessUnit, vendor, productType, shiftBand, trips,
                vehicles, firstSeen, lastSeen, observedOta, avgDelayMinutes, sla, v);
    }
}
