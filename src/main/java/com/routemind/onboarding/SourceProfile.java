package com.routemind.onboarding;

import java.util.List;
import java.util.Map;

/** Artefacts of the onboarding pipeline. */
public final class SourceProfile {

    private SourceProfile() {}

    /** What we observed about one incoming column. */
    public record ColumnProfile(String name,
                                String inferredType,      // TEXT | INTEGER | DECIMAL | TIMESTAMP | BOOLEAN
                                double nonNullPct,
                                long distinctCount,
                                List<String> sampleValues) {}

    /** One proposed column -> canonical field mapping. */
    public record FieldMapping(String canonicalField,
                               String sourceColumn,
                               double confidence,
                               String tier,               // TIER1_ALIAS | TIER2_PATTERN | TIER3_LLM | UNRESOLVED
                               String note) {}

    /** The full proposal a human confirms during onboarding. */
    public record Proposal(String sourceId,
                           List<FieldMapping> mappings,
                           List<String> unmappedColumns,
                           Map<String, Boolean> capabilities,
                           List<String> warnings) {}
}
