package com.routemind.onboarding;

import com.routemind.onboarding.SourceProfile.ColumnProfile;
import com.routemind.onboarding.SourceProfile.FieldMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Onboarding: can we recognise an unfamiliar export without being told?
 * Tier 1/2 must resolve the common cases so the LLM is only asked about leftovers.
 */
@DisplayName("FieldIdentifier — three-tier column identification")
class FieldIdentifierTest {

    final FieldIdentifier id = new FieldIdentifier();

    static ColumnProfile col(String name, String... samples) {
        return new ColumnProfile(name, "TEXT", 100.0, samples.length, List.of(samples));
    }

    static Map<String, FieldMapping> byField(List<FieldMapping> ms) {
        return ms.stream().collect(Collectors.toMap(FieldMapping::canonicalField, m -> m));
    }

    @Test
    void recognisesTheRealMoveInSyncColumns() {
        var mappings = byField(id.identify(List.of(
                col("trip_id", "1516906"),
                col("vendor_id", "Rohan Mikhailov Travel"),
                col("business_unit", "catalyst-Sac"),
                col("office", "Santa Clara Office"),
                col("shift_type", "09:00"),
                col("delay_minutes", "0"),
                col("actual_cab_capacity", "4"))));

        assertEquals("trip_id", mappings.get("tripId").sourceColumn());
        assertEquals("vendor_id", mappings.get("vendor").sourceColumn());
        assertEquals("business_unit", mappings.get("businessUnit").sourceColumn());
        assertEquals("delay_minutes", mappings.get("delayMinutes").sourceColumn());
        assertEquals("TIER1_ALIAS", mappings.get("tripId").tier());
    }

    @Test
    void recognisesRenamedColumnsFromADifferentVendorExport() {
        // the same concepts under names we have never seen
        var mappings = byField(id.identify(List.of(
                col("ride_id", "1516906"),
                col("supplier", "Acme Cabs"),
                col("tenant", "acme-blr"),
                col("site", "Whitefield"),
                col("journey_type", "LOGIN"))));

        assertEquals("ride_id", mappings.get("tripId").sourceColumn(),
                "ride_id is a known alias for tripId");
        assertEquals("supplier", mappings.get("vendor").sourceColumn());
        assertEquals("tenant", mappings.get("businessUnit").sourceColumn());
        assertEquals("site", mappings.get("office").sourceColumn());
    }

    @Test
    void identifiesEpochTimestampsByValuePattern() {
        var mappings = byField(id.identify(List.of(
                col("weird_ts_name_actual", "1782864858", "1782864900"))));
        assertTrue(mappings.containsKey("actualStart") || mappings.containsKey("plannedStart"),
                "a 10-digit epoch column should be recognised as a time field");
    }

    @Test
    void identifiesDirectionByItsTwoValues() {
        var mappings = byField(id.identify(List.of(
                new ColumnProfile("leg", "TEXT", 100.0, 2, List.of("LOGIN", "LOGOUT")))));
        assertEquals("leg", mappings.get("direction").sourceColumn());
        assertEquals("TIER2_PATTERN", mappings.get("direction").tier());
    }

    @Test
    void aliasMatchingIgnoresCaseAndPunctuation() {
        var mappings = byField(id.identify(List.of(col("Trip-ID", "1"), col("VENDOR_NAME", "x"))));
        assertTrue(mappings.containsKey("tripId"));
        assertTrue(mappings.containsKey("vendor"));
    }

    @Test
    void trulyUnknownColumnsAreLeftForTierThree() {
        List<ColumnProfile> cols = List.of(
                col("trip_id", "1"),
                col("zx_internal_flag_9", "purple", "green"));
        var mappings = id.identify(cols);
        List<String> unresolved = id.unresolved(cols, mappings);
        assertTrue(unresolved.contains("zx_internal_flag_9"),
                "unrecognised columns must be surfaced, not silently dropped");
        assertFalse(unresolved.contains("trip_id"));
    }

    @Test
    void oneCanonicalFieldIsNotClaimedTwice() {
        var mappings = id.identify(List.of(col("trip_id", "1"), col("ride_id", "2")));
        long tripIdMappings = mappings.stream()
                .filter(m -> m.canonicalField().equals("tripId")).count();
        assertEquals(1, tripIdMappings, "the first match wins; no duplicate claims");
    }

    @Test
    void emptyInputIsSafe() {
        assertTrue(id.identify(List.of()).isEmpty());
    }
}
