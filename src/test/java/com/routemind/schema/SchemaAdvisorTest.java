package com.routemind.schema;

import com.routemind.llm.LlmChat;
import com.routemind.schema.SchemaChange.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deterministic half of the schema advisor.
 *
 * The LLM is the nice path, but it is optional — no API key, no network, a rate limit or a
 * malformed reply all land here. Onboarding must still produce a usable proposal, so the
 * heuristic is not a nicety: it is what makes the LLM safe to depend on at all.
 *
 * These tests pin the fallback's behaviour and, more importantly, its SAFE DEFAULT:
 * a column it does not recognise is never recommended for adoption.
 */
class SchemaAdvisorTest {

    /** enabled=false and no key, so ask() short-circuits — nothing leaves the JVM. */
    static final SchemaAdvisor OFFLINE =
            new SchemaAdvisor(new LlmChat(false, "", "m", "http://localhost/none"));

    static Profile p(String type, double fill, long distinct, String... samples) {
        return new Profile(type, fill, distinct, List.of(samples));
    }

    /** Parses the four-line contract the UI renders. */
    static Map<String, String> parse(String proposal) {
        return proposal.lines()
                .filter(l -> l.contains(":") && l.matches("^[A-Z]+:.*"))
                .collect(Collectors.toMap(
                        l -> l.substring(0, l.indexOf(':')).trim(),
                        l -> l.substring(l.indexOf(':') + 1).trim(),
                        (a, b) -> a));
    }

    static String recommendation(String column, Profile prof) {
        return parse(OFFLINE.heuristic("trips", column, prof)).get("RECOMMEND");
    }

    @Nested
    @DisplayName("Output contract — the UI parses four fixed keys")
    class Contract {

        @Test
        void alwaysEmitsMeaningRecommendWhyAndMetric() {
            Map<String, String> f = parse(OFFLINE.heuristic("trips", "anything", p("TEXT", 50, 40000)));
            assertEquals(4, f.size(), "the UI renders exactly these four fields: " + f.keySet());
            assertTrue(f.keySet().containsAll(List.of("MEANING", "RECOMMEND", "WHY", "METRIC")));
        }

        @Test
        void recommendationIsOnlyEverAdoptOrIgnore() {
            // never REJECT, and never a free-text verb the UI cannot map to a button
            for (String col : List.of("trip_cost", "extra_km", "picked_up_at", "driver_rating",
                    "supplier_code", "co2_grams", "is_verified", "mystery_field")) {
                String r = recommendation(col, p("TEXT", 90, 40000));
                assertTrue(List.of("ADOPT", "IGNORE").contains(r), col + " -> " + r);
            }
        }

        @Test
        void saysPlainlyThatNoModelWasUsed() {
            String out = OFFLINE.heuristic("trips", "x", p("TEXT", 50, 5));
            assertTrue(out.contains("heuristic"), "the operator must know this was not the model");
            assertTrue(out.contains("a human decides"));
        }

        @Test
        void reportsThatItIsNotUsingAModel() {
            assertFalse(OFFLINE.usingModel());
        }

        @Test
        void proposeFallsBackToTheHeuristicWhenTheModelIsUnavailable() {
            String proposal = OFFLINE.propose("trips", "trip_cost", p("NUMBER", 100, 9000));
            assertEquals(OFFLINE.heuristic("trips", "trip_cost", p("NUMBER", 100, 9000)), proposal);
        }
    }

    @Nested
    @DisplayName("Recognises the concepts we already model")
    class Recognition {

        @Test
        void moneyLikeNamesMapToCostAnalysis() {
            for (String col : List.of("surcharge_amount", "night_fare", "toll_cost", "unit_price")) {
                Map<String, String> f = parse(OFFLINE.heuristic("billing", col, p("NUMBER", 90, 5000)));
                assertEquals("ADOPT", f.get("RECOMMEND"), col);
                assertEquals("cost analysis", f.get("METRIC"), col);
            }
        }

        @Test
        void distanceNamesMapToCostPerKm() {
            assertEquals("cost per km / route efficiency",
                    parse(OFFLINE.heuristic("trips", "deadhead_km", p("NUMBER", 90, 5000))).get("METRIC"));
        }

        @Test
        void experienceScoresMapToTheExperienceMetric() {
            assertEquals("employee experience",
                    parse(OFFLINE.heuristic("feedback", "driver_rating", p("NUMBER", 90, 5))).get("METRIC"));
        }

        @Test
        void sustainabilityNamesMapToEmissions() {
            assertEquals("emissions / EV share",
                    parse(OFFLINE.heuristic("trips", "co2_grams", p("NUMBER", 90, 9000))).get("METRIC"));
        }

        @Test
        void supplySideNamesMapToVendorAttribution() {
            assertEquals("vendor performance attribution",
                    parse(OFFLINE.heuristic("trips", "vehicle_age_years", p("NUMBER", 90, 900))).get("METRIC"));
        }

        @Test
        void timestampSuffixIsReadAsATimestamp() {
            assertEquals("punctuality or duration",
                    parse(OFFLINE.heuristic("trips", "cancelled_at", p("TIMESTAMP", 90, 9000))).get("METRIC"));
        }

        @Test
        void atIsMatchedAsASuffixNotAsASubstring() {
            // "employee_attendance" contains "_at" but is not a timestamp — matching it as a
            // substring would have mislabelled it and proposed a punctuality metric
            assertNotEquals("punctuality or duration",
                    parse(OFFLINE.heuristic("trip_employees", "employee_attendance",
                            p("TEXT", 90, 40000))).get("METRIC"));
        }
    }

    @Nested
    @DisplayName("Safe defaults — silence beats a confident wrong guess")
    class SafeDefaults {

        @Test
        void anUnrecognisedHighCardinalityColumnIsNotAdopted() {
            Map<String, String> f = parse(
                    OFFLINE.heuristic("trips", "zx_ref_token", p("TEXT", 100, 480_000)));
            assertEquals("IGNORE", f.get("RECOMMEND"),
                    "adopting something we cannot explain is how a wrong metric gets born");
            assertEquals("NONE", f.get("METRIC"));
            assertTrue(f.get("MEANING").toLowerCase().contains("review with the data owner"));
        }

        @Test
        void anAlmostEmptyColumnIsNotAdopted() {
            Map<String, String> f = parse(
                    OFFLINE.heuristic("trips", "zx_pilot_ref", p("TEXT", 0.4, 5_000)));
            assertEquals("IGNORE", f.get("RECOMMEND"));
            assertTrue(f.get("MEANING").toLowerCase().contains("empty"));
        }

        @Test
        void aSmallRepeatedValueSetIsOfferedAsANewBreakdown() {
            Map<String, String> f = parse(
                    OFFLINE.heuristic("trips", "zx_tier", p("TEXT", 98, 4, "A", "B", "C", "D")));
            assertEquals("ADOPT", f.get("RECOMMEND"));
            assertEquals("a new breakdown dimension", f.get("METRIC"));
        }

        @Test
        void theEvidenceIsAlwaysShownSoTheHumanCanOverruleIt() {
            String why = parse(OFFLINE.heuristic("trips", "zx_tier", p("TEXT", 98.5, 4))).get("WHY");
            assertTrue(why.contains("zx_tier"));
            assertTrue(why.contains("98.5"), "fill rate must be visible: " + why);
            assertTrue(why.contains("4"), "distinct count must be visible: " + why);
        }
    }
}
