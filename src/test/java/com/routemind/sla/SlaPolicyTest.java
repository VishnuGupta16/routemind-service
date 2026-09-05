package com.routemind.sla;

import com.routemind.sla.SlaPolicy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SLA scoping, precedence and the tolerance band. Pure logic — no DB.
 *
 * This is the part most likely to be subtly wrong: if precedence resolves the wrong way, a
 * vendor gets judged against someone else's contract and every downstream verdict is
 * invalid while still looking perfectly plausible.
 */
class SlaPolicyTest {

    /** Full scope: business unit, vendor, cab type, exact shift, shift band. */
    static SlaPolicy policy(String name, String bu, String vendor, String product,
                            String shift, String band, int window, double target) {
        return new SlaPolicy(1L, name, bu, vendor, product, shift, band, window, target,
                null, null, 0, null, null, true, null, "test");
    }

    /** The common case — scoped by vendor / cab type / shift band. */
    static SlaPolicy banded(String name, String vendor, String product, String band,
                            int window, double target) {
        return policy(name, null, vendor, product, null, band, window, target);
    }

    /** Mirrors the SQL ordering in SlaPolicyService.SPECIFICITY_ORDER. */
    static SlaPolicy resolve(List<SlaPolicy> all, String bu, String vendor, String product,
                             String shift, String band, LocalDate on) {
        return all.stream()
                .filter(p -> p.businessUnit() == null || p.businessUnit().equals(bu))
                .filter(p -> p.vendor() == null || p.vendor().equals(vendor))
                .filter(p -> p.productType() == null || p.productType().equals(product))
                .filter(p -> p.shiftType() == null || p.shiftType().equals(shift))
                .filter(p -> p.shiftBand() == null || p.shiftBand().equals(band))
                .filter(p -> p.appliesOn(on))
                .max(Comparator.comparingInt(SlaPolicy::specificity)
                        .thenComparingInt(SlaPolicy::priority))
                .orElse(null);
    }

    @Nested
    @DisplayName("Specificity — vendor beats BU beats cab type beats shift beats band")
    class Specificity {

        @Test
        void wildcardPolicyIsLeastSpecific() {
            assertEquals(0, policy("all", null, null, null, null, null, 10, 95).specificity());
        }

        @Test
        void eachDimensionOutranksTheNextOneDown() {
            int vendor = policy("v", null, "V1", null, null, null, 10, 95).specificity();
            int bu = policy("b", "bu1", null, null, null, null, 10, 95).specificity();
            int product = policy("p", null, null, "BUS", null, null, 10, 95).specificity();
            int shift = policy("s", null, null, null, "09:00", null, 10, 95).specificity();
            int band = policy("z", null, null, null, null, "MORNING", 10, 95).specificity();

            assertTrue(vendor > bu, "a vendor contract must beat a tenant-wide rule");
            assertTrue(bu > product);
            assertTrue(product > shift);
            assertTrue(shift > band, "an exact shift time is a narrower commitment than a band");
        }

        @Test
        void vendorAloneStillBeatsEveryOtherDimensionCombined() {
            // 16 > 8+4+2+1 — a signed vendor contract wins over any combination of scopes
            int vendorOnly = policy("v", null, "V1", null, null, null, 10, 95).specificity();
            int everythingElse =
                    policy("x", "bu1", null, "BUS", "09:00", "MORNING", 10, 95).specificity();
            assertTrue(vendorOnly > everythingElse);
        }

        @Test
        void moreScopesAlwaysMeansMoreSpecific() {
            assertTrue(policy("a", "bu1", "V1", "BUS", "09:00", "MORNING", 10, 95).specificity()
                     > policy("b", "bu1", "V1", "BUS", "09:00", null, 10, 95).specificity());
        }

        @Test
        void weightsAreDistinctPowersOfTwoSoNoCombinationCanTie() {
            // if two different scope sets could tie, resolution would fall through to
            // priority and pick essentially at random
            int[] weights = {16, 8, 4, 2, 1};
            java.util.Set<Integer> sums = new java.util.HashSet<>();
            for (int mask = 0; mask < 32; mask++) {
                int s = 0;
                for (int b = 0; b < 5; b++) if ((mask & (1 << b)) != 0) s += weights[b];
                assertTrue(sums.add(s), "two scope combinations share specificity " + s);
            }
        }
    }

    @Nested
    @DisplayName("Resolution — the right contract is applied")
    class Resolution {
        final LocalDate day = LocalDate.of(2026, 7, 15);

        final List<SlaPolicy> all = List.of(
                policy("Group default", null, null, null, null, null, 10, 95.0),
                policy("Bus SLA", null, null, "BUS", null, null, 15, 90.0),
                policy("Morning band", null, null, null, null, "MORNING", 15, 92.0),
                policy("Premium vendor", null, "Isha", null, null, null, 5, 97.0));

        @Test
        void fallsBackToGroupDefaultWhenNothingSpecificMatches() {
            SlaPolicy p = resolve(all, "bu1", "Unknown", "CAB", "20:00", "EVENING", day);
            assertEquals("Group default", p.name());
            assertEquals(10, p.otaWindowMinutes());
        }

        @Test
        void cabTypeRuleBeatsGroupDefault() {
            SlaPolicy p = resolve(all, "bu1", "Unknown", "BUS", "20:00", "EVENING", day);
            assertEquals("Bus SLA", p.name());
            assertEquals(15, p.otaWindowMinutes(), "buses get the looser window they signed");
        }

        @Test
        void cabTypeRuleAlsoBeatsAShiftBandRule() {
            // a BUS running in the MORNING matches both; cab type is the stronger scope
            SlaPolicy p = resolve(all, "bu1", "Unknown", "BUS", "09:00", "MORNING", day);
            assertEquals("Bus SLA", p.name());
        }

        @Test
        void bandRuleAppliesWhenNoCabTypeRuleMatches() {
            SlaPolicy p = resolve(all, "bu1", "Unknown", "CAB", "09:00", "MORNING", day);
            assertEquals("Morning band", p.name());
            assertEquals(92.0, p.otaTarget(),
                    "morning runs at 92.3% group-wide, so a 95% morning target would "
                            + "breach permanently and tell nobody anything");
        }

        @Test
        void vendorContractBeatsEveryScopedRule() {
            // Isha running a BUS in the morning: the vendor's own contract must still win
            SlaPolicy p = resolve(all, "bu1", "Isha", "BUS", "09:00", "MORNING", day);
            assertEquals("Premium vendor", p.name());
            assertEquals(5, p.otaWindowMinutes());
            assertEquals(97.0, p.otaTarget());
        }

        @Test
        void aVendorCanPassGloballyAndStillMissItsOwnContract() {
            // 95.4% clears the group 95% target but misses a 97% premium commitment
            double achieved = 95.4;
            SlaPolicy group = resolve(all, "bu1", "Other", "CAB", "20:00", "EVENING", day);
            SlaPolicy premium = resolve(all, "bu1", "Isha", "CAB", "20:00", "EVENING", day);

            assertEquals(Verdict.MET, group.verdict(achieved), "passes the group rule");
            assertNotEquals(Verdict.MET, premium.verdict(achieved),
                    "the same performance must not read as MET against a 97% commitment");
            // 1.6 points short of 97 is inside the default ±2 band, so it is a warning
            assertEquals(Verdict.AT_RISK, premium.verdict(achieved));
            assertEquals(1.6, premium.shortfall(achieved), 1e-9);
        }

        @Test
        void aPremiumContractWithATightBandTurnsTheSameMissIntoABreach() {
            // identical performance, identical target — only the tolerance differs
            SlaPolicy loose = new SlaPolicy(1L, "loose", null, "Isha", null, null, null,
                    5, 97.0, 2.0, null, 0, null, null, true, null, "t");
            SlaPolicy tight = new SlaPolicy(2L, "tight", null, "Isha", null, null, null,
                    5, 97.0, 0.5, null, 0, null, null, true, null, "t");

            assertEquals(Verdict.AT_RISK, loose.verdict(95.4));
            assertEquals(Verdict.BREACH, tight.verdict(95.4));
        }
    }

    @Nested
    @DisplayName("Tolerance — the +/- deviation allowed before the verdict changes")
    class Tolerance {

        final SlaPolicy p = banded("Premium", "V1", "CAB", "MORNING", 10, 95.0);

        @Test
        void defaultsToTwoPointsWhenThePolicyDoesNotSetOne() {
            assertEquals(SlaPolicy.DEFAULT_TOLERANCE_PCT, p.tolerance());
        }

        @Test
        void atOrAboveTargetIsMet() {
            assertEquals(Verdict.MET, p.verdict(95.0));
            assertEquals(Verdict.MET, p.verdict(99.9));
        }

        @Test
        void justInsideTheBandIsAtRiskNotBreach() {
            assertEquals(Verdict.AT_RISK, p.verdict(94.9));
            assertEquals(Verdict.AT_RISK, p.verdict(93.0), "exactly on the band edge");
        }

        @Test
        void outsideTheBandIsBreach() {
            assertEquals(Verdict.BREACH, p.verdict(92.99));
            assertEquals(Verdict.BREACH, p.verdict(80.0));
        }

        @Test
        void aTighterContractCanSetATighterBand() {
            SlaPolicy strict = new SlaPolicy(1L, "strict", null, "V1", null, null, null,
                    5, 97.0, 0.5, null, 0, null, null, true, null, "t");
            assertEquals(0.5, strict.tolerance());
            assertEquals(Verdict.AT_RISK, strict.verdict(96.6));
            assertEquals(Verdict.BREACH, strict.verdict(96.4),
                    "a premium contract should not get the same slack as a shuttle one");
        }

        @Test
        void aZeroOrNegativeToleranceFallsBackRatherThanMakingEveryMissABreach() {
            SlaPolicy zero = new SlaPolicy(1L, "z", null, "V1", null, null, null,
                    10, 95.0, 0.0, null, 0, null, null, true, null, "t");
            assertEquals(SlaPolicy.DEFAULT_TOLERANCE_PCT, zero.tolerance());
        }

        @Test
        void shortfallIsZeroWhenTheTargetIsMet() {
            assertEquals(0.0, p.shortfall(96.0));
            assertEquals(2.0, p.shortfall(93.0), 1e-9);
        }
    }

    @Nested
    @DisplayName("Temporal — history is scored against the SLA in force at the time")
    class Temporal {

        SlaPolicy dated(LocalDate from, LocalDate to) {
            return new SlaPolicy(1L, "term", null, "V1", null, null, null, 10, 95.0,
                    null, null, 0, from, to, true, null, "test");
        }

        @Test
        void policyDoesNotApplyBeforeItStarts() {
            assertFalse(dated(LocalDate.of(2026, 6, 1), null)
                    .appliesOn(LocalDate.of(2026, 5, 15)));
        }

        @Test
        void policyDoesNotApplyAfterItEnds() {
            assertFalse(dated(null, LocalDate.of(2026, 6, 30))
                    .appliesOn(LocalDate.of(2026, 7, 1)));
        }

        @Test
        void openEndedPolicyAppliesIndefinitely() {
            assertTrue(dated(LocalDate.of(2026, 1, 1), null)
                    .appliesOn(LocalDate.of(2027, 12, 31)));
        }

        @Test
        void boundariesAreInclusive() {
            SlaPolicy p = dated(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
            assertTrue(p.appliesOn(LocalDate.of(2026, 6, 1)));
            assertTrue(p.appliesOn(LocalDate.of(2026, 6, 30)));
        }

        @Test
        void renegotiationSplitsThePeriodCorrectly() {
            // old terms to 30 Jun, new terms from 1 Jul — June and July score differently
            SlaPolicy oldTerms = new SlaPolicy(1L, "old", null, "V1", null, null, null,
                    15, 90.0, null, null, 0, null, LocalDate.of(2026, 6, 30), true, null, "t");
            SlaPolicy newTerms = new SlaPolicy(2L, "new", null, "V1", null, null, null,
                    5, 97.0, null, null, 0, LocalDate.of(2026, 7, 1), null, true, null, "t");
            List<SlaPolicy> all = List.of(oldTerms, newTerms);

            assertEquals("old", resolve(all, "b", "V1", "CAB", "09:00", "MORNING",
                    LocalDate.of(2026, 6, 15)).name());
            assertEquals("new", resolve(all, "b", "V1", "CAB", "09:00", "MORNING",
                    LocalDate.of(2026, 7, 15)).name());
        }

        @Test
        void theSameMonthCanScoreDifferentlyUnderTheTwoTerms() {
            SlaPolicy oldTerms = new SlaPolicy(1L, "old", null, "V1", null, null, null,
                    15, 90.0, null, null, 0, null, LocalDate.of(2026, 6, 30), true, null, "t");
            SlaPolicy newTerms = new SlaPolicy(2L, "new", null, "V1", null, null, null,
                    5, 97.0, null, null, 0, LocalDate.of(2026, 7, 1), null, true, null, "t");
            double achieved = 93.5;
            assertEquals(Verdict.MET, oldTerms.verdict(achieved));
            assertEquals(Verdict.BREACH, newTerms.verdict(achieved),
                    "same performance, different contract, opposite verdict — which is why "
                            + "history must not be rescored under today's terms");
        }

        @Test
        void inactivePolicyNeverApplies() {
            SlaPolicy off = new SlaPolicy(1L, "off", null, "V1", null, null, null, 10, 95.0,
                    null, null, 0, null, null, false, null, "t");
            assertFalse(off.appliesOn(LocalDate.of(2026, 7, 1)));
        }
    }

    @Nested
    @DisplayName("Labels are readable in the UI and in narratives")
    class Labels {

        @Test
        void wildcardPolicyReadsAsGroupDefault() {
            assertEquals("all trips (group default)",
                    policy("d", null, null, null, null, null, 10, 95).scopeLabel());
        }

        @Test
        void scopedPolicyListsItsDimensions() {
            String label = policy("x", "vanta-Sea", "Isha", "BUS", "09:00", "MORNING", 5, 97)
                    .scopeLabel();
            assertTrue(label.contains("Isha"));
            assertTrue(label.contains("vanta-Sea"));
            assertTrue(label.contains("BUS"));
            assertTrue(label.contains("09:00"));
            assertTrue(label.contains("morning"));
        }

        @Test
        void termsLabelShowsTargetWindowAndTolerance() {
            String terms = banded("x", "V1", "BUS", "MORNING", 15, 92.0).termsLabel();
            assertTrue(terms.contains("92.0%"), terms);
            assertTrue(terms.contains("15 min"), terms);
            assertTrue(terms.contains("2.0"), terms);
        }
    }

    @Nested
    @DisplayName("VendorFleet — a combination is only scored when it is big enough to judge")
    class Fleet {

        VendorFleet combo(long trips, Double ota) {
            return new VendorFleet(1L, "bu1", "V1", "BUS", "MORNING", trips, 4,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 31), ota, 6.0, null, null);
        }

        @Test
        void aTinyCombinationIsNotGivenAVerdict() {
            VendorFleet f = combo(12, 50.0).withSla(banded("x", "V1", "BUS", "MORNING", 10, 95));
            assertFalse(f.judgeable());
            assertNull(f.verdict(), "12 trips at 50% tells you nothing — do not score it");
            assertNotNull(f.appliedSla(), "but it must still show which SLA would apply");
        }

        @Test
        void aCombinationWithEnoughTripsIsScored() {
            VendorFleet f = combo(5_000, 92.9).withSla(banded("x", "V1", "BUS", "MORNING", 10, 95));
            assertTrue(f.judgeable());
            assertEquals(Verdict.BREACH, f.verdict());
        }

        @Test
        void aCombinationWithNoSlaHasNoVerdictRatherThanADefaultPass() {
            VendorFleet f = combo(5_000, 92.9).withSla(null);
            assertNull(f.verdict(), "no contract means no verdict, not an implied pass");
        }

        @Test
        void labelIsReadable() {
            assertEquals("V1 · BUS · morning", combo(100, 95.0).label());
        }
    }
}
