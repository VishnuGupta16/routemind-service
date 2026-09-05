package com.routemind.schema;

import com.routemind.llm.LlmChat;
import com.routemind.schema.SchemaChange.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Works out what an unfamiliar new column probably MEANS and what to do about it.
 *
 * This is a genuinely good use of the LLM: the input is fuzzy (a column name plus a few
 * sample values), it happens once per column ever — so cost is irrelevant — and a human
 * still approves the decision. If the model is unavailable or wrong, the deterministic
 * heuristic below still produces a sensible proposal, so onboarding never blocks.
 */
@Service
public class SchemaAdvisor {

    private static final String SYSTEM = """
            You are a data engineer reviewing a new column that appeared in an enterprise
            employee-transport data feed (trips, riders, vendors, billing, safety alerts).
            Given the column name, inferred type and sample values, reply in EXACTLY this form:

            MEANING: <one sentence, what this column most likely represents>
            RECOMMEND: <ADOPT or IGNORE>
            WHY: <one sentence justification>
            METRIC: <a metric it could support, or NONE>

            Be concise and concrete. Do not invent facts beyond the samples shown.
            """;

    private final LlmChat llm;

    public SchemaAdvisor(LlmChat llm) { this.llm = llm; }

    /** Returns the proposal text shown to the human in the UI. */
    public String propose(String source, String column, Profile p) {
        return llm.ask(SYSTEM, prompt(source, column, p), 220, "schema-advice")
                .map(s -> s + "\n\n(assessed by model; a human decides)")
                .orElseGet(() -> heuristic(source, column, p));
    }

    private String prompt(String source, String column, Profile p) {
        return """
                Table: %s
                Column: %s
                Inferred type: %s
                Populated: %.1f%% of rows
                Distinct values: %d
                Samples: %s
                """.formatted(source, column, p.inferredType(), p.nonNullPct(),
                p.distinctCount(), p.sampleValues());
    }

    /**
     * Deterministic fallback — pattern-matches the column name against concepts we
     * already model. Not as nuanced as the model, but never wrong in a surprising way.
     */
    String heuristic(String source, String column, Profile p) {
        String c = column.toLowerCase(Locale.ROOT);
        String meaning = "Unrecognised field; review with the data owner.";
        String recommend = "IGNORE";
        String metric = "NONE";

        if (containsAny(c, "cost", "amount", "fare", "price", "charge")) {
            meaning = "Looks like a monetary amount.";
            recommend = "ADOPT"; metric = "cost analysis";
        } else if (containsAny(c, "km", "distance", "mileage")) {
            meaning = "Looks like a distance measure.";
            recommend = "ADOPT"; metric = "cost per km / route efficiency";
        // "_at" is deliberately a SUFFIX test: as a substring it also matches
        // employee_attendance, seat_attribute and similar, which are not timestamps.
        } else if (containsAny(c, "time", "epoch", "date", "timestamp") || c.endsWith("_at")) {
            meaning = "Looks like a timestamp.";
            recommend = "ADOPT"; metric = "punctuality or duration";
        } else if (containsAny(c, "rating", "score", "feedback", "nps")) {
            meaning = "Looks like an experience score.";
            recommend = "ADOPT"; metric = "employee experience";
        } else if (containsAny(c, "vendor", "supplier", "driver", "cab", "vehicle")) {
            meaning = "Looks like a supply-side identifier or attribute.";
            recommend = "ADOPT"; metric = "vendor performance attribution";
        } else if (containsAny(c, "co2", "emission", "fuel", "electric", "ev")) {
            meaning = "Looks like a sustainability attribute.";
            recommend = "ADOPT"; metric = "emissions / EV share";
        } else if (containsAny(c, "flag", "is_", "has_", "_nc", "violation", "alert")) {
            meaning = "Looks like a boolean flag or compliance indicator.";
            recommend = "ADOPT"; metric = "compliance rate";
        } else if (p.distinctCount() > 0 && p.distinctCount() <= 12) {
            meaning = "Small set of repeated values — likely a new category/enum.";
            recommend = "ADOPT"; metric = "a new breakdown dimension";
        } else if (p.nonNullPct() < 5.0) {
            meaning = "Almost entirely empty — probably not in use yet.";
            recommend = "IGNORE";
        }

        return """
                MEANING: %s
                RECOMMEND: %s
                WHY: %s populated on %.1f%% of rows with %d distinct values.
                METRIC: %s

                (heuristic assessment — no model configured; a human decides)"""
                .formatted(meaning, recommend, column, p.nonNullPct(), p.distinctCount(), metric);
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    public boolean usingModel() { return llm.available(); }
}
