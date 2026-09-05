package com.routemind.sla;

import java.time.LocalDate;

/**
 * A configurable SLA, set when a vendor is onboarded.
 *
 * Any scope field left null is a WILDCARD, so one group-wide default can be overridden only
 * where a contract actually differs. Resolution picks the most specific match:
 * vendor (16) &gt; business unit (8) &gt; product type (4) &gt; exact shift (2) &gt; shift band (1),
 * ties broken by {@code priority} then the most recent {@code effectiveFrom}.
 *
 * An exact shift time outranks a band because it is the narrower commitment. In practice
 * you configure against the band — there are 100 distinct shift times but only 6 bands, and
 * the bands are where performance actually separates (MORNING 92.3% vs EARLY 99.4%).
 *
 * The dates make it temporal: renegotiating a contract adds a row rather than overwriting
 * one, so historical months stay scored against the SLA that was actually in force then —
 * which matters the moment anyone disputes a penalty.
 */
public record SlaPolicy(
        Long id,
        String name,
        String businessUnit,      // null = any
        String vendor,            // null = any
        String productType,       // null = any
        String shiftType,         // null = any exact shift time, e.g. "09:30"
        String shiftBand,         // null = any band
        int otaWindowMinutes,
        double otaTarget,
        Double tolerancePct,      // null = fall back to the global at-risk margin
        Double noShowTarget,
        int priority,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean active,
        String notes,
        String createdBy) {

    /** Used when a policy does not set its own tolerance. Mirrors routemind.sla.at-risk-margin. */
    public static final double DEFAULT_TOLERANCE_PCT = 2.0;

    public enum Verdict { MET, AT_RISK, BREACH }

    /** How specific this policy is — higher wins during resolution. */
    public int specificity() {
        return (vendor != null ? 16 : 0)
                + (businessUnit != null ? 8 : 0)
                + (productType != null ? 4 : 0)
                + (shiftType != null ? 2 : 0)
                + (shiftBand != null ? 1 : 0);
    }

    /** The +/- deviation allowed around the target before the verdict changes. */
    public double tolerance() {
        return tolerancePct == null || tolerancePct <= 0 ? DEFAULT_TOLERANCE_PCT : tolerancePct;
    }

    /**
     * Score an achieved on-time rate against this contract.
     *
     * The band exists so that "missed by 0.2 points" and "missed by 6 points" are not the
     * same word. Calling both BREACH is what makes a scorecard shout constantly and get
     * ignored; calling both MET is what lets a real slide go unnoticed for a quarter.
     */
    public Verdict verdict(double achievedOta) {
        if (achievedOta >= otaTarget) return Verdict.MET;
        return achievedOta >= otaTarget - tolerance() ? Verdict.AT_RISK : Verdict.BREACH;
    }

    /** Points below target; 0 when the target is met. */
    public double shortfall(double achievedOta) {
        return Math.max(0, otaTarget - achievedOta);
    }

    /** Human-readable scope, for the UI and for narratives. */
    public String scopeLabel() {
        StringBuilder sb = new StringBuilder();
        if (vendor != null) sb.append(vendor);
        if (businessUnit != null) sb.append(sb.isEmpty() ? "" : " · ").append(businessUnit);
        if (productType != null) sb.append(sb.isEmpty() ? "" : " · ").append(productType);
        if (shiftType != null) sb.append(sb.isEmpty() ? "" : " · ").append("shift ").append(shiftType);
        if (shiftBand != null) sb.append(sb.isEmpty() ? "" : " · ").append(shiftBand.toLowerCase());
        return sb.isEmpty() ? "all trips (group default)" : sb.toString();
    }

    /** e.g. "95.0% within 10 min (±2.0)". */
    public String termsLabel() {
        return String.format("%.1f%% within %d min (±%.1f)", otaTarget, otaWindowMinutes, tolerance());
    }

    public boolean appliesOn(LocalDate d) {
        if (!active) return false;
        if (effectiveFrom != null && d.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !d.isAfter(effectiveTo);
    }
}
