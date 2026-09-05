package com.routemind.chat;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Works out which persona a free-text question is coming from.
 *
 * The three personas want fundamentally different answers to the same numbers — the
 * facilities head a budget-and-contract story, the transport manager a what-broke-today
 * signal, the line manager their own team's readiness — so the first thing the chatbot must
 * decide is who is asking. Get this wrong and a perfectly correct set of numbers is framed
 * for the wrong person.
 *
 * Two stages, model first with a deterministic floor:
 *   1. the model reads the question and picks a persona (nuance, phrasing, intent)
 *   2. if the model is unavailable or answers oddly, keyword scoring decides
 * The keyword stage alone is good enough for the demo, so the chatbot classifies sensibly
 * with no API key at all.
 */
@Component
public class PersonaClassifier {

    public record Result(String personaCode, String source, String rationale) {}

    private final ChatModelClient model;

    public PersonaClassifier(ChatModelClient model) { this.model = model; }

    private static final String SYSTEM = """
            Classify who is asking a question about an employee-transport operation into ONE
            of exactly these persona codes:

            FACILITIES_HEAD  — strategic: budget, cost, vendor contracts, SLA accountability,
                               leadership reporting, "should we renegotiate / cut spend".
            TRANSPORT_MANAGER— operational: what is going wrong right now, which vendor to
                               chase, shift planning, delays today, "why is X down".
            LINE_MANAGER     — team-level: their own team's pickups, no-shows, who was late.

            Reply with the persona code and nothing else.
            Question: %s
            """;

    /** Keyword cues, scored. Deliberately small and readable — it is a floor, not the brain. */
    private static final List<String> FACILITIES = List.of(
            "budget", "cost", "spend", "contract", "renegotiat", "penalt",
            "vendor strategy", "leadership", "board", "invoice", "billing",
            "sla accountab", "overall", "quarter", "month");
    private static final List<String> TRANSPORT = List.of(
            "why is", "why did", "down", "drop", "today", "yesterday", "now",
            "right now", "reroute", "reassign", "chase", "which vendor", "breach",
            "degrad", "spike", "sudden", "shift", "route", "delay");
    private static final List<String> LINE = List.of(
            "my team", "my people", "our team", "my shift", "who was late",
                    "our pickups", "my riders", "did everyone");

    public Result classify(String question) {
        return model.ask(SYSTEM.formatted(question))
                .map(String::trim)
                .map(PersonaClassifier::normalise)
                .filter(PersonaClassifier::isPersona)
                .map(code -> new Result(code, "LLM", "classified by the model"))
                .orElseGet(() -> keyword(question));
    }

    private static String normalise(String s) {
        String up = s.toUpperCase(Locale.ROOT);
        if (up.contains("FACILITIES")) return "FACILITIES_HEAD";
        if (up.contains("TRANSPORT")) return "TRANSPORT_MANAGER";
        if (up.contains("LINE")) return "LINE_MANAGER";
        return up.replaceAll("[^A-Z_]", "");
    }

    private static boolean isPersona(String code) {
        return code.equals("FACILITIES_HEAD") || code.equals("TRANSPORT_MANAGER")
                || code.equals("LINE_MANAGER");
    }

    private Result keyword(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        int f = score(q, FACILITIES), t = score(q, TRANSPORT), l = score(q, LINE);

        // Default to the transport manager: "why is X down / bad" is the commonest question
        // and the operational persona is the one built to answer it.
        String code = "TRANSPORT_MANAGER";
        int best = t;
        if (f > best) { code = "FACILITIES_HEAD"; best = f; }
        if (l > best) { code = "LINE_MANAGER"; best = l; }

        String why = best == 0
                ? "no strong cue — defaulted to the operational persona"
                : "keyword match (facilities=%d, transport=%d, line=%d)".formatted(f, t, l);
        return new Result(code, "keyword", why);
    }

    private static int score(String q, List<String> groups) {
        int s = 0;
        for (String g : groups) if (q.contains(g)) s++;
        return s;
    }
}
