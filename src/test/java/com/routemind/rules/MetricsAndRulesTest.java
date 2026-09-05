package com.routemind.rules;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.Projection;
import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.metrics.spi.MetricDefinition.Direction;
import com.routemind.predict.ErrorBudgetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic core: projection, ranking, rule firing. No DB, no Spring. */
class MetricsAndRulesTest {

    // ---------------------------------------------------------------- helpers
    static RuleSetProperties rules() {
        RuleSetProperties r = new RuleSetProperties();
        r.getSla().setOtaWindowMinutes(10);
        r.getSla().setAtRiskMargin(2.0);
        r.getTrigger().setTrendDropUnits(3.0);
        return r;
    }

    static MetricWithContext metric(double value, double target, Double prior,
                                    Status status, Direction dir, long n) {
        Double vsTarget = value - target;
        Double vsPrior = prior == null ? null : value - prior;
        return new MetricWithContext("ota", "On-time arrival", "percent", dir.name(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, n,
                value, target, prior, vsTarget, vsPrior, status, "vendor",
                List.of(new Contribution("Vendor A", 900, 13.3)), null,
                "headline text");
    }

    // ------------------------------------------------------------ error budget
    @Nested
    @DisplayName("ErrorBudgetService — turns a lagging metric into a prediction")
    class Budget {
        final ErrorBudgetService svc = new ErrorBudgetService();
        final LocalDate start = LocalDate.of(2026, 7, 1);
        final LocalDate end = LocalDate.of(2026, 7, 30);

        @Test
        void healthyMetricIsOk() {
            Projection p = svc.project(98.0, 95.0, Direction.HIGHER_IS_BETTER,
                    start, end, start.plusDays(7));
            assertEquals(Status.OK, p.status());
            assertNull(p.projectedBreachDate(), "healthy metric should not project a breach");
        }

        @Test
        void breachingMetricIsFlagged() {
            Projection p = svc.project(84.0, 95.0, Direction.HIGHER_IS_BETTER,
                    start, end, start.plusDays(7));
            assertEquals(Status.BREACH, p.status());
            assertTrue(p.burnRate() > 1.0, "burning budget faster than the period elapses");
        }

        @Test
        void lowerIsBetterMetricInverts() {
            // no-show rate 6% against a 3% target = breach
            Projection p = svc.project(6.0, 3.0, Direction.LOWER_IS_BETTER,
                    start, end, start.plusDays(10));
            assertEquals(Status.BREACH, p.status());
        }

        @Test
        void breachDateFallsInsideThePeriod() {
            Projection p = svc.project(80.0, 95.0, Direction.HIGHER_IS_BETTER,
                    start, end, start.plusDays(5));
            if (p.projectedBreachDate() != null) {
                assertFalse(p.projectedBreachDate().isAfter(end));
                assertFalse(p.projectedBreachDate().isBefore(start));
            }
        }
    }

    // ---------------------------------------------------------------- ranking
    @Nested
    @DisplayName("InsightRanker — bigger gaps and more data rank higher")
    class Ranking {
        final InsightRanker ranker = new InsightRanker(rules());

        @Test
        void biggerGapOutranksSmallerGap() {
            double big = ranker.materiality(
                    metric(80, 95, 90.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 10_000),
                    Finding.Severity.HIGH);
            double small = ranker.materiality(
                    metric(94, 95, 95.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 10_000),
                    Finding.Severity.HIGH);
            assertTrue(big > small, "a 15-point miss must outrank a 1-point miss");
        }

        @Test
        void moreDataRaisesConfidence() {
            double lots = ranker.materiality(
                    metric(90, 95, null, Status.BREACH, Direction.HIGHER_IS_BETTER, 500_000),
                    Finding.Severity.HIGH);
            double few = ranker.materiality(
                    metric(90, 95, null, Status.BREACH, Direction.HIGHER_IS_BETTER, 10),
                    Finding.Severity.HIGH);
            assertTrue(lots > few, "the same gap over more trips should rank higher");
        }

        @Test
        void severityIsWeighted() {
            MetricWithContext m = metric(90, 95, null, Status.BREACH,
                    Direction.HIGHER_IS_BETTER, 1000);
            assertTrue(ranker.materiality(m, Finding.Severity.HIGH)
                     > ranker.materiality(m, Finding.Severity.LOW));
        }

        @Test
        void personaRelevanceDownweightsUnownedMetrics() {
            RuleSetProperties r = rules();
            r.getPersonas().put("LINE_MANAGER", List.of("ota", "no_show_rate"));
            InsightRanker ir = new InsightRanker(r);
            assertEquals(1.0, ir.personaRelevance("ota", "LINE_MANAGER"));
            assertTrue(ir.personaRelevance("cost_per_trip", "LINE_MANAGER") < 1.0);
        }
    }

    // ------------------------------------------------------------ rule firing
    @Nested
    @DisplayName("RuleEvaluator — decides what is worth raising")
    class Rules {
        final RuleSetProperties r = rules();
        final RuleEvaluator evaluator = new RuleEvaluator(r, new InsightRanker(r));

        @Test
        void breachRaisesHighSeverity() {
            List<Finding> f = evaluator.evaluate(List.of(
                    metric(90, 95, 96.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 5000)));
            assertEquals(1, f.size());
            assertEquals(Finding.Severity.HIGH, f.get(0).severity());
            assertEquals("BELOW_TARGET", f.get(0).reason());
        }

        @Test
        void healthyMetricRaisesNothing() {
            List<Finding> f = evaluator.evaluate(List.of(
                    metric(99, 95, 98.0, Status.OK, Direction.HIGHER_IS_BETTER, 5000)));
            assertTrue(f.isEmpty(), "a healthy metric must not create noise");
        }

        @Test
        void sharpFallIsCaughtEvenWhenAboveTarget() {
            // 97 is above the 95 target, but it fell 5 points — worth knowing
            List<Finding> f = evaluator.evaluate(List.of(
                    metric(97, 95, 102.0, Status.OK, Direction.HIGHER_IS_BETTER, 5000)));
            assertEquals(1, f.size());
            assertEquals("FALLING", f.get(0).reason());
        }

        @Test
        void emptyDataIsSkipped() {
            assertTrue(evaluator.evaluate(List.of(
                    metric(0, 95, null, Status.OK, Direction.HIGHER_IS_BETTER, 0))).isEmpty());
        }

        @Test
        void findingsAreSortedByMateriality() {
            List<Finding> f = evaluator.evaluate(List.of(
                    metric(94, 95, 95.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 100),
                    metric(70, 95, 95.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 100_000)));
            assertEquals(2, f.size());
            assertTrue(f.get(0).materiality() >= f.get(1).materiality());
        }

        @Test
        void findingCarriesItsEvidence() {
            Finding f = evaluator.evaluate(List.of(
                    metric(90, 95, 96.0, Status.BREACH, Direction.HIGHER_IS_BETTER, 5000)))
                    .get(0);
            assertEquals(90.0, f.value());
            assertEquals(95.0, f.target());
            assertEquals(96.0, f.priorValue());
            assertFalse(f.attribution().isEmpty(), "attribution must survive into the finding");
            assertNotNull(f.dedupeKey());
        }
    }
}
