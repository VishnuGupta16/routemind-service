package com.routemind.narrative;

import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.persona.Persona;
import com.routemind.rules.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The safety-critical contract: the narrative layer may rephrase numbers but never
 * invent them, and it must always produce something even with no LLM.
 */
class NarrativeAndPersonaTest {

    static Finding finding(String metricId, double value, double target, Double prior) {
        return new Finding("F-1", metricId, "On-time arrival", Finding.Severity.HIGH,
                "BELOW_TARGET", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                "catalyst-Sac", value, target, prior, Status.BREACH, "vendor",
                List.of(new Contribution("Vendor A", 900, 13.3)), null, 0.9,
                "On-time arrival is 93.2% ... Vendor A accounts for 13.3% of the impact.",
                "");
    }

    @Nested
    @DisplayName("TemplateNarrativeGenerator — always available, always grounded")
    class Template {
        final TemplateNarrativeGenerator gen = new TemplateNarrativeGenerator();

        @Test
        void producesTextWithoutAnyLlm() {
            String out = gen.narrate(finding("ota", 93.2, 95.0, 96.0), "TRANSPORT_MANAGER");
            assertNotNull(out);
            assertFalse(out.isBlank());
        }

        @Test
        void everyNumberInOutputCameFromTheFinding() {
            Finding f = finding("ota", 93.2, 95.0, 96.0);
            String out = gen.narrate(f, "FACILITIES_HEAD");
            // the template builds from evidence + attribution; both are on the finding
            assertTrue(out.contains("93.2"), "value must be preserved");
            assertTrue(out.contains("13.3"), "attribution must be preserved");
        }

        @Test
        void recommendsAMetricAppropriateAction() {
            assertTrue(TemplateNarrativeGenerator.action(finding("ota", 90, 95, null))
                    .toLowerCase().contains("sla review"));
            assertTrue(TemplateNarrativeGenerator.action(finding("cost_per_trip", 1200, 1000, null))
                    .toLowerCase().contains("slab"));
            assertTrue(TemplateNarrativeGenerator.action(finding("seat_utilisation", 40, 70, null))
                    .toLowerCase().contains("consolidat"));
            assertTrue(TemplateNarrativeGenerator.action(finding("ev_share", 5, 25, null))
                    .toLowerCase().contains("electric"));
        }

        @Test
        void unknownMetricHasNoInventedAction() {
            assertNull(TemplateNarrativeGenerator.action(finding("made_up_metric", 1, 2, null)));
        }

        @Test
        void isAlwaysAvailableAsTheFallback() {
            assertTrue(gen.available());
            assertEquals(0, gen.priority(), "template must lose to the LLM when one exists");
        }
    }

    @Nested
    @DisplayName("NarrativeService — picks the best generator and caches")
    class Service {

        @Test
        void fallsBackToTemplateWhenNothingElseIsAvailable() {
            NarrativeService svc = new NarrativeService(List.of(new TemplateNarrativeGenerator()));
            assertEquals("TemplateNarrativeGenerator", svc.activeGenerator());
        }

        @Test
        void narrationIsCachedPerFindingAndPersona() {
            NarrativeService svc = new NarrativeService(List.of(new TemplateNarrativeGenerator()));
            Finding f = finding("ota", 93.2, 95.0, 96.0);
            svc.narrate(f, "FACILITIES_HEAD");
            svc.narrate(f, "FACILITIES_HEAD");        // same key -> no second generation
            assertEquals(1, svc.cacheSize(), "repeat views must not cost a second inference");
            svc.narrate(f, "LINE_MANAGER");           // different persona -> new entry
            assertEquals(2, svc.cacheSize());
        }

        @Test
        void narrationIsAttachedToTheFinding() {
            NarrativeService svc = new NarrativeService(List.of(new TemplateNarrativeGenerator()));
            Finding out = svc.narrate(finding("ota", 93.2, 95.0, 96.0), "TRANSPORT_MANAGER");
            assertFalse(out.narrative().isBlank());
            assertEquals(93.2, out.value(), "attaching narrative must not alter the numbers");
        }

        /** A named stand-in for "the LLM generator" — named so activeGenerator() (which
         *  reports getClass().getSimpleName()) returns something we can actually assert on.
         *  An anonymous class would silently report "" here (that's the JLS behaviour for
         *  Class.getSimpleName() on an anonymous class), which is what made this test wrong
         *  rather than the selection logic it was meant to catch. */
        static final class FakeLlmGenerator implements NarrativeGenerator {
            public String narrate(Finding f, String persona) { return "LLM"; }
            public int priority() { return 10; }
        }

        @Test
        void higherPriorityGeneratorWins() {
            NarrativeService svc = new NarrativeService(
                    List.of(new TemplateNarrativeGenerator(), new FakeLlmGenerator()));
            assertEquals("FakeLlmGenerator", svc.activeGenerator(),
                    "the higher-priority generator should be selected");
        }
    }

    @Nested
    @DisplayName("Persona — three lenses, config-overridable")
    class Personas {

        @Test
        void allThreePersonasExist() {
            assertEquals(3, Persona.values().length);
        }

        @Test
        void parsingIsForgiving() {
            assertEquals(Persona.LINE_MANAGER, Persona.of("line_manager"));
            assertEquals(Persona.LINE_MANAGER, Persona.of("LINE-MANAGER"));
            assertEquals(Persona.FACILITIES_HEAD, Persona.of(null), "sane default");
        }

        @Test
        void eachPersonaOwnsMetricsAndHasACadence() {
            for (Persona p : Persona.values()) {
                assertFalse(p.defaultMetrics().isEmpty(), p + " must own metrics");
                assertNotNull(p.cadence());
                assertNotNull(p.channel());
                assertFalse(p.need().isBlank());
            }
        }

        @Test
        void facilitiesHeadHasTheWidestScope() {
            assertTrue(Persona.FACILITIES_HEAD.defaultMetrics().size()
                     > Persona.LINE_MANAGER.defaultMetrics().size());
        }

        @Test
        void operationalPersonasAreNotOnAWeeklyCadence() {
            assertEquals(Persona.Cadence.REALTIME, Persona.TRANSPORT_MANAGER.cadence());
            assertEquals(Persona.Cadence.PER_SHIFT, Persona.LINE_MANAGER.cadence());
            assertEquals(Persona.Cadence.WEEKLY, Persona.FACILITIES_HEAD.cadence());
        }
    }
}
