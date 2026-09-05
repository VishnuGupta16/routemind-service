package com.routemind.diagnose;

import com.routemind.diagnose.MetricDegradationService.Shape;
import com.routemind.diagnose.MetricDegradationService.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A slide is only worth raising when the metric is close to the target it would breach.
 *
 * These pin the case that produced a real bad alert: "Electric vehicle share is sliding to
 * 10.9% (target 9.0%)" flagged as "a trend to get ahead of before it breaches" while the
 * value sat 21% clear of the floor.
 */
class AlertBandTest {

    private static Signal signal(String id, double latest, double target, String status) {
        return new Signal(id, id, "percent", Shape.INCREMENTAL, status, latest, target,
                -0.2, 0.0, 25.0, null, null, "test", List.of());
    }

    @Test
    @DisplayName("a slide comfortably clear of its target is not alertable")
    void healthySlideIsIgnored() {
        // the actual bad alert: 10.9% against a 9.0% floor is 21% clear
        assertFalse(MetricDegradationService.nearTarget(
                signal("ev_share", 10.9, 9.0, "OK")));
        // and no-show at 5.4% against an 8.0% ceiling is further clear still
        assertFalse(MetricDegradationService.nearTarget(
                signal("no_show_rate", 5.4, 8.0, "OK")));
    }

    @Test
    @DisplayName("a slide inside the band is alertable — it is about to breach")
    void slideNearTargetIsRaised() {
        assertTrue(MetricDegradationService.nearTarget(
                signal("ev_share", 9.5, 9.0, "OK")));
        assertTrue(MetricDegradationService.nearTarget(
                signal("ota", 95.4, 95.0, "OK")));
    }

    @Test
    @DisplayName("anything already at risk or breaching is always alertable")
    void breachAlwaysRaised() {
        assertTrue(MetricDegradationService.nearTarget(
                signal("cost_per_trip", 5000, 1400, "BREACH")));
        assertTrue(MetricDegradationService.nearTarget(
                signal("ota", 80.0, 95.0, "AT_RISK")));
    }

    @Test
    @DisplayName("the band is proportional, so it works for ratings and rupees alike")
    void bandIsProportional() {
        // rating: 4.85 target, 10% band = 0.485
        assertTrue(MetricDegradationService.nearTarget(
                signal("experience", 5.0, 4.85, "OK")));
        // cost: 1400 target, 10% band = 140
        assertFalse(MetricDegradationService.nearTarget(
                signal("cost_per_trip", 1000, 1400, "OK")));
    }
}
