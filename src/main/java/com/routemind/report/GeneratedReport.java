package com.routemind.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One produced report, before it is stored or sent.
 *
 * The shape matters as much as the content. A report is a headline, a body, an action —
 * and a list of {@link Fact}s that every sentence in the body was derived from. Nothing may
 * appear in the prose that is not in the facts.
 *
 * That rule is why the facts are a first-class field rather than a debugging extra. Today it
 * keeps the deterministic writer honest and lets any claim be re-run. In Phase 2, when an
 * LLM writes the prose, it is the entire input — the model gets the facts and the persona's
 * prompt, never the raw database, so it cannot invent a number that was never measured.
 */
public record GeneratedReport(
        String alertCode,
        String personaCode,
        String businessUnit,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate compareStart,
        LocalDate compareEnd,
        String headline,
        String body,
        String recommendedAction,
        double severityScore,
        boolean actionable,
        String generatedBy,          // RULES | LLM | RULES+LLM
        List<Fact> facts) {

    /**
     * One measured number with its reference point. "Every metric carries context" is a
     * hard requirement of the brief, so there is deliberately no way to record a value here
     * without also recording what it should be compared against.
     */
    public record Fact(
            String metricId,
            String dimension,          // vendor | product_type | shift_type | office | null
            String dimensionValue,
            Double value,
            String unit,               // percent | currency | rate | rating | count
            Long sampleSize,           // a rate without its denominator is not a fact
            Double referenceValue,
            String referenceKind,      // SLA | TARGET | PRIOR_PERIOD | PEER | BENCHMARK
            String referenceLabel,
            Double delta,
            String direction,          // UP | DOWN | FLAT
            String verdict,            // MET | AT_RISK | BREACH | INFO
            Double contribution,       // how much this drove the severity score
            String evidenceSql) {      // the query behind the number

        public static Builder of(String metricId) { return new Builder(metricId); }

        /** JSONB payload for generated_report.facts — what a Phase 2 LLM reads. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", metricId);
            if (dimension != null) {
                m.put("dimension", dimension);
                m.put("dimensionValue", dimensionValue);
            }
            m.put("value", value);
            m.put("unit", unit);
            m.put("sampleSize", sampleSize);
            m.put("referenceValue", referenceValue);
            m.put("referenceKind", referenceKind);
            m.put("referenceLabel", referenceLabel);
            m.put("delta", delta);
            m.put("direction", direction);
            m.put("verdict", verdict);
            return m;
        }

        public static final class Builder {
            private final String metricId;
            private String dimension, dimensionValue, unit, referenceKind, referenceLabel;
            private String direction = "FLAT", verdict = "INFO", evidenceSql;
            private Double value, referenceValue, delta, contribution;
            private Long sampleSize;

            private Builder(String metricId) { this.metricId = metricId; }

            public Builder on(String dim, String val) {
                this.dimension = dim; this.dimensionValue = val; return this;
            }
            public Builder value(Double v, String u, Long n) {
                this.value = v; this.unit = u; this.sampleSize = n; return this;
            }
            /** Records the reference AND derives delta/direction, so they cannot disagree. */
            public Builder against(Double ref, String kind, String label) {
                this.referenceValue = ref; this.referenceKind = kind; this.referenceLabel = label;
                if (value != null && ref != null) {
                    this.delta = Math.round((value - ref) * 100.0) / 100.0;
                    this.direction = delta > 0.0001 ? "UP" : delta < -0.0001 ? "DOWN" : "FLAT";
                }
                return this;
            }
            public Builder verdict(String v) { this.verdict = v; return this; }
            public Builder contribution(double c) { this.contribution = c; return this; }
            public Builder evidence(String sql) { this.evidenceSql = sql; return this; }

            public Fact build() {
                return new Fact(metricId, dimension, dimensionValue, value, unit, sampleSize,
                        referenceValue, referenceKind, referenceLabel, delta, direction,
                        verdict, contribution, evidenceSql);
            }
        }
    }

    /** Facts as the JSONB payload, plus the period they describe. */
    public Map<String, Object> factsPayload() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Fact f : facts) list.add(f.toMap());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", Map.of("start", String.valueOf(periodStart),
                "end", String.valueOf(periodEnd)));
        m.put("comparedWith", compareStart == null ? null
                : Map.of("start", String.valueOf(compareStart), "end", String.valueOf(compareEnd)));
        m.put("businessUnit", businessUnit);
        m.put("facts", list);
        return m;
    }
}
