package com.routemind.live;

import com.routemind.live.Live.RiskLevel;
import com.routemind.live.LiveAlertService.LiveAlert;
import com.routemind.live.LiveEtaService.Prediction;
import com.routemind.rules.RuleSetProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Live GPS ingestion + in-flight alerting. No Kafka, no DB. */
class LiveLayerTest {

    static GpsEvent ping(long trip, double lat, double lon, double speed, Instant at) {
        return new GpsEvent(trip, lat, lon, speed, 90.0, at);
    }

    static RuleSetProperties rules() {
        RuleSetProperties r = new RuleSetProperties();
        r.getSla().setOtaWindowMinutes(10);
        return r;
    }

    @Nested
    @DisplayName("LivePositionStore — accumulates distance from the GPS stream")
    class Positions {

        @Test
        void haversineMatchesKnownDistance() {
            // Bengaluru MG Road -> Whitefield, ~15-19 km apart
            double km = LivePositionStore.haversineKm(12.9750, 77.6060, 12.9698, 77.7500);
            assertTrue(km > 14 && km < 19, "expected ~15-19km, got " + km);
        }

        @Test
        void identicalPointsAreZeroDistance() {
            assertEquals(0.0, LivePositionStore.haversineKm(12.9, 77.6, 12.9, 77.6), 1e-9);
        }

        @Test
        void coveredDistanceAccumulatesAcrossPings() {
            LivePositionStore store = new LivePositionStore();
            Instant t = Instant.parse("2026-07-15T07:30:00Z");
            store.accept(ping(1, 12.9000, 77.6000, 20, t));
            store.accept(ping(1, 12.9200, 77.6000, 22, t.plusSeconds(60)));
            store.accept(ping(1, 12.9400, 77.6000, 24, t.plusSeconds(120)));

            LivePositionStore.TripState s = store.state(1).orElseThrow();
            assertEquals(3, s.samples);
            assertTrue(s.coveredKm > 3.5 && s.coveredKm < 5.5,
                    "two ~2.2km hops should total ~4.4km, got " + s.coveredKm);
        }

        @Test
        void gpsJitterIsIgnored() {
            LivePositionStore store = new LivePositionStore();
            Instant t = Instant.parse("2026-07-15T07:30:00Z");
            store.accept(ping(2, 12.9000, 77.6000, 0, t));
            // 1-metre wobble while stationary at a light
            store.accept(ping(2, 12.900002, 77.600002, 0, t.plusSeconds(30)));
            assertEquals(0.0, store.state(2).orElseThrow().coveredKm, 1e-6,
                    "sub-5m jitter must not accumulate as travel");
        }

        @Test
        void impossibleJumpIsRejected() {
            LivePositionStore store = new LivePositionStore();
            Instant t = Instant.parse("2026-07-15T07:30:00Z");
            store.accept(ping(3, 12.9000, 77.6000, 20, t));
            store.accept(ping(3, 19.0760, 72.8777, 20, t.plusSeconds(30)));  // Mumbai
            assertEquals(0.0, store.state(3).orElseThrow().coveredKm, 1e-6,
                    "a 800km jump between pings is a bad fix, not travel");
        }

        @Test
        void speedIsSmoothedAcrossRecentPings() {
            LivePositionStore store = new LivePositionStore();
            Instant t = Instant.parse("2026-07-15T07:30:00Z");
            store.accept(ping(4, 12.90, 77.60, 40, t));
            store.accept(ping(4, 12.91, 77.60, 0, t.plusSeconds(30)));   // stopped at a light
            double smoothed = store.state(4).orElseThrow().smoothedSpeedKph();
            assertTrue(smoothed > 0 && smoothed < 40,
                    "smoothing should sit between the samples, got " + smoothed);
        }

        @Test
        void unknownTripHasNoPosition() {
            assertTrue(new LivePositionStore().positionOf(999).isEmpty());
        }

        @Test
        void staleTripsAreEvicted() {
            LivePositionStore store = new LivePositionStore();
            Instant old = Instant.parse("2026-07-15T05:00:00Z");
            store.accept(ping(5, 12.9, 77.6, 10, old));
            int evicted = store.evictStale(Duration.ofHours(1), old.plusSeconds(7200));
            assertEquals(1, evicted);
            assertTrue(store.inFlight().isEmpty());
        }

        @Test
        void invalidEventsAreRejectedByValidation() {
            assertFalse(new GpsEvent(0, 12.9, 77.6, 10, null, Instant.now()).valid());
            assertFalse(new GpsEvent(1, 99.0, 77.6, 10, null, Instant.now()).valid());
            assertFalse(new GpsEvent(1, 12.9, 77.6, 10, null, null).valid());
            assertTrue(new GpsEvent(1, 12.9, 77.6, 10, null, Instant.now()).valid());
        }
    }

    @Nested
    @DisplayName("LiveAlertService — alerts once, escalates only on material change")
    class Alerting {

        Prediction pred(long trip, double delay, double confidence, RiskLevel level) {
            return new Prediction(trip, delay, delay, delay, 0.7, 1.2, 0.5,
                    confidence, level, 4, "test-basis");
        }

        @Test
        void belowThresholdDoesNotAlert() {
            LiveAlertService svc = new LiveAlertService(rules());
            assertTrue(svc.consider(pred(1, 4, 0.9, RiskLevel.LOW)).isEmpty(),
                    "4 min delay is inside the 10 min SLA window");
        }

        @Test
        void lowConfidenceIsHeldBack() {
            LiveAlertService svc = new LiveAlertService(rules());
            assertTrue(svc.consider(pred(1, 30, 0.2, RiskLevel.HIGH)).isEmpty(),
                    "an unsure prediction must not wake anyone");
        }

        @Test
        void firstBreachAlerts() {
            LiveAlertService svc = new LiveAlertService(rules());
            Optional<LiveAlert> a = svc.consider(pred(1, 18, 0.8, RiskLevel.MEDIUM));
            assertTrue(a.isPresent());
            assertEquals(18.0, a.get().predictedDelayMin());
            assertEquals(4, a.get().employeesAffected());
            assertNotNull(a.get().recommendedAction());
        }

        @Test
        void sameTripDoesNotAlertTwice() {
            LiveAlertService svc = new LiveAlertService(rules());
            assertTrue(svc.consider(pred(1, 18, 0.8, RiskLevel.MEDIUM)).isPresent());
            assertTrue(svc.consider(pred(1, 19, 0.8, RiskLevel.MEDIUM)).isEmpty(),
                    "a 1-minute worsening is not worth a second alert");
        }

        @Test
        void materialWorseningEscalates() {
            LiveAlertService svc = new LiveAlertService(rules());
            svc.consider(pred(1, 15, 0.8, RiskLevel.MEDIUM));
            Optional<LiveAlert> second = svc.consider(pred(1, 25, 0.8, RiskLevel.HIGH));
            assertTrue(second.isPresent(), "+10 min is a material change");
            assertTrue(second.get().message().startsWith("Escalating"));
        }

        @Test
        void highRiskRecommendsReassignment() {
            LiveAlertService svc = new LiveAlertService(rules());
            LiveAlert a = svc.consider(pred(1, 40, 0.9, RiskLevel.HIGH)).orElseThrow();
            assertTrue(a.recommendedAction().toLowerCase().contains("reassign"));
        }

        @Test
        void subscribersReceivePushedAlerts() {
            LiveAlertService svc = new LiveAlertService(rules());
            var received = new java.util.ArrayList<LiveAlert>();
            svc.subscribe(received::add);
            svc.consider(pred(7, 22, 0.9, RiskLevel.HIGH));
            assertEquals(1, received.size());
            assertEquals(7, received.get(0).tripId());
        }

        @Test
        void recentFeedIsNewestFirst() {
            LiveAlertService svc = new LiveAlertService(rules());
            svc.consider(pred(1, 20, 0.9, RiskLevel.HIGH));
            svc.consider(pred(2, 20, 0.9, RiskLevel.HIGH));
            assertEquals(2, svc.recent(10).get(0).tripId());
        }
    }
}
