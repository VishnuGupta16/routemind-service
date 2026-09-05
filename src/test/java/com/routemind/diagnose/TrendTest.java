package com.routemind.diagnose;

import com.routemind.diagnose.Trend.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The degradation shape classifier.
 *
 * This is the logic that tells a transport manager "this broke today" apart from "this has
 * been sliding for weeks". Getting it wrong in either direction is costly in a specific way:
 * calling a slide sudden sends someone chasing a phantom incident; calling a sudden break a
 * slide means the incident is never chased and just becomes next month's baseline. So both
 * confusions are tested explicitly, in both metric polarities.
 */
class TrendTest {

    static Shape shape(double[] series, boolean higherBetter) {
        return Trend.classify(series, higherBetter).shape();
    }

    @Nested
    @DisplayName("Needs enough history to say anything")
    class Data {
        @Test
        void fewerThanThreeBucketsIsInsufficient() {
            assertEquals(Shape.INSUFFICIENT_DATA, shape(new double[]{95, 96}, true));
        }
    }

    @Nested
    @DisplayName("Higher-is-better metrics (OTA, utilisation)")
    class HigherBetter {

        @Test
        void aFlatRunIsStable() {
            assertEquals(Shape.STABLE, shape(new double[]{96.1, 96.0, 95.9, 96.0, 96.1}, true));
        }

        @Test
        void aSuddenDropInTheLastBucketIsSudden() {
            // steady ~96, then a cliff to 89 — an incident
            assertEquals(Shape.SUDDEN, shape(new double[]{96, 96.2, 95.8, 96.1, 89.0}, true));
        }

        @Test
        void aSteadyDeclineIsIncremental() {
            // sliding down a point a bucket, no single cliff
            assertEquals(Shape.INCREMENTAL,
                    shape(new double[]{97, 96, 95, 94, 93}, true));
        }

        @Test
        void aSteadyRiseIsImproving() {
            assertEquals(Shape.IMPROVING, shape(new double[]{92, 93, 94, 95, 96}, true));
        }

        @Test
        void aSlideThatEndsInACliffIsReportedAsSudden() {
            // both are present; the incident must win so it gets chased today
            assertEquals(Shape.SUDDEN, shape(new double[]{97, 96.5, 96, 95.5, 88}, true));
        }
    }

    @Nested
    @DisplayName("Lower-is-better metrics (cost, no-show, safety) — polarity flips")
    class LowerBetter {

        @Test
        void aSuddenRiseIsSudden() {
            // cost per trip steady ~1300 then jumps to 1600 — worse, and sudden
            assertEquals(Shape.SUDDEN,
                    shape(new double[]{1300, 1310, 1295, 1305, 1600}, false));
        }

        @Test
        void aSteadyRiseIsIncremental() {
            assertEquals(Shape.INCREMENTAL,
                    shape(new double[]{1200, 1250, 1300, 1350, 1400}, false));
        }

        @Test
        void aSteadyFallIsImproving() {
            // no-show rate steadily dropping is good
            assertEquals(Shape.IMPROVING, shape(new double[]{9, 8.5, 8, 7.5, 7}, false));
        }

        @Test
        void aFallingCostIsNotFlaggedAsDegrading() {
            assertNotEquals(Shape.SUDDEN, shape(new double[]{1500, 1400, 1300, 1200, 1100}, false));
            assertNotEquals(Shape.INCREMENTAL,
                    shape(new double[]{1500, 1400, 1300, 1200, 1100}, false));
        }
    }

    @Nested
    @DisplayName("Noise does not become a signal")
    class Noise {

        @Test
        void subTwoPercentWiggleIsStable() {
            // 96.0 +/- 0.3 is jitter, not a trend, and must not page anyone
            assertEquals(Shape.STABLE,
                    shape(new double[]{96.0, 96.3, 95.8, 96.2, 95.9}, true));
        }

        @Test
        void aSmallLastBucketBlipInsideTheRunSpreadIsNotSudden() {
            // last bucket dips but well within how much the run already bounces around
            Shape s = shape(new double[]{96, 94, 97, 93, 95}, true);
            assertNotEquals(Shape.SUDDEN, s, "a blip no bigger than the run's own spread "
                    + "is not an incident");
        }
    }

    @Nested
    @DisplayName("The worsening direction is always positive, whatever the polarity")
    class Direction {

        @Test
        void aDropInAHigherBetterMetricReadsAsWorsening() {
            Trend.Result r = Trend.classify(new double[]{97, 96, 95, 94, 90}, true);
            assertTrue(r.worsenSlope() > 0, "falling OTA must read as a positive worsen-slope");
        }

        @Test
        void aRiseInALowerBetterMetricReadsAsWorsening() {
            Trend.Result r = Trend.classify(new double[]{1200, 1300, 1400, 1500, 1600}, false);
            assertTrue(r.worsenSlope() > 0, "rising cost must read as a positive worsen-slope");
        }
    }

    @Nested
    @DisplayName("The OLS slope itself")
    class Slope {
        @Test
        void isTheRatePerBucket() {
            assertEquals(1.0, Trend.ols(new double[]{1, 2, 3, 4, 5}), 1e-9);
            assertEquals(-2.0, Trend.ols(new double[]{10, 8, 6, 4, 2}), 1e-9);
            assertEquals(0.0, Trend.ols(new double[]{5, 5, 5, 5}), 1e-9);
        }
    }
}
