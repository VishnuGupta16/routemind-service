package com.routemind.chat;

import com.routemind.llm.LlmChat;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides HOW to answer a question: which existing service to call, and only as a last
 * resort, whether a bounded SQL query is needed.
 *
 * The order is deliberate and is the whole point of this class:
 *
 *   1. AN EXISTING API FIRST. Every service here already applies the SLA policy, the
 *      product exclusions and the attribution rules. A hand-written query would silently
 *      skip all of that and produce a number that disagrees with the dashboard — which is
 *      far worse than not answering.
 *   2. SQL ONLY IF NO API FITS, and then over a NARROW, already-filtered slice, never the
 *      whole database (see SafeSqlExecutor).
 *   3. AT MOST TWO ATTEMPTS. If a second try still fails we answer from whatever the APIs
 *      did return and say the detail was unavailable. An agent that retries indefinitely
 *      turns one bad question into an unbounded bill.
 *
 * The model only PICKS from this fixed menu — it never invents a tool, and it never
 * produces the numbers itself.
 */
@Component
public class QueryPlanner {

    /** The tools the planner is allowed to choose. Each maps to a real service call. */
    public enum Tool {
        /** OTA decomposition: direction, shift, product, office, vendor, cause mix. */
        OTA_ROOT_CAUSE,
        /** Every metric's trend shape and urgency — "what is going wrong". */
        DEGRADING_METRICS,
        /** Vendor scorecard against the SLA each vendor actually signed. */
        SLA_COMPLIANCE,
        /** One metric with its target, prior period and top contributors. */
        METRIC_WITH_CONTEXT,
        /** Per-employee shift readiness — who was late, who did not board. */
        SHIFT_READINESS,
        /** Who misses the target WEEK AFTER WEEK, rather than badly once. */
        REPEAT_OFFENDERS,
        /** Which vendors carry actual financial penalties, and how concentrated. */
        VENDOR_PENALTIES,
        /** No API covers it; fall through to a bounded SQL read. */
        SQL_FALLBACK
    }

    /**
     * A slice of the data the question asked about. Null fields mean "not filtered".
     *
     * Extracting these is not optional detail: "how is OTA on the night shift" and "how is
     * OTA" are different questions, and answering the second when the first was asked is a
     * wrong answer with the right arithmetic behind it.
     */
    public record Filters(String shiftBand, String direction, String productType,
                          String vendor, String office) {

        public static final Filters NONE = new Filters(null, null, null, null, null);

        public boolean any() {
            return shiftBand != null || direction != null || productType != null
                    || vendor != null || office != null;
        }

        /** Human-readable, for the answer to state what it actually measured. */
        public String label() {
            List<String> parts = new java.util.ArrayList<>();
            if (shiftBand != null) parts.add(shiftBand.toLowerCase(Locale.ROOT) + " shift");
            if (direction != null) parts.add(direction.equals("LOGIN")
                    ? "morning pickup (LOGIN)" : "evening drop (LOGOUT)");
            if (productType != null) parts.add(productType);
            if (vendor != null) parts.add(vendor);
            if (office != null) parts.add(office);
            return String.join(", ", parts);
        }
    }

    public record Plan(Tool tool, String metricId, String rationale, String source,
                       Filters filters) {

        public Plan(Tool tool, String metricId, String rationale, String source) {
            this(tool, metricId, rationale, source, Filters.NONE);
        }
    }

    /** Hard ceiling on planning attempts, per the "max 2 attempts" rule. */
    public static final int MAX_ATTEMPTS = 2;

    private final LlmChat llm;

    public QueryPlanner(LlmChat llm) { this.llm = llm; }

    private static final String SYSTEM = """
            You route a question about an employee-transport operation to exactly ONE tool.
            Reply with the tool name alone — no punctuation, no explanation.

            OTA_ROOT_CAUSE      why on-time arrival moved, and which slice caused it
            DEGRADING_METRICS   what is going wrong across all metrics right now
            SLA_COMPLIANCE      which vendors missed the contract they signed; penalties
            METRIC_WITH_CONTEXT one named metric's current value, target and contributors
                                (add a second line: the metric id)
            SHIFT_READINESS     who on a team was late, did not board, or no-showed
            REPEAT_OFFENDERS    who is CONSISTENTLY bad — recurring, every week, a pattern
                                rather than a single bad period
            VENDOR_PENALTIES    which vendor is charged the most in penalties (money)
            SQL_FALLBACK        none of the above can answer it

            Prefer a specific tool over SQL_FALLBACK. Never invent a tool name.
            """;

    /**
     * Pick a tool. The model goes first (it reads intent), keywords are the floor, and the
     * floor alone is good enough to run with no API key at all.
     */
    public Plan plan(String question) {
        Filters f = extractFilters(question);
        Optional<String> answer = llm.ask(SYSTEM, "Question: " + question, 40, "tool-routing");
        if (answer.isPresent()) {
            Plan p = parse(answer.get(), question);
            if (p != null) {
                return new Plan(p.tool(), p.metricId(), p.rationale(), p.source(), f);
            }
        }
        Plan k = keywordPlan(question);
        return new Plan(k.tool(), k.metricId(), k.rationale(), k.source(), f);
    }

    /**
     * Pull the slice out of the question. Deterministic on purpose — a filter is a fact
     * about what was asked, not a judgement call, and getting it from the model would make
     * "which rows did we measure" non-reproducible.
     */
    static Filters extractFilters(String question) {
        String q = question.toLowerCase(Locale.ROOT);

        String band = null;
        // Longest/most specific first: "early morning" must not match as MORNING.
        if (q.contains("early morning") || q.contains("early shift")) band = "EARLY";
        else if (q.contains("night")) band = "NIGHT";
        else if (q.contains("morning")) band = "MORNING";
        else if (q.contains("midday") || q.contains("mid-day") || q.contains("afternoon")) band = "MIDDAY";
        else if (q.contains("evening")) band = "EVENING";

        String direction = null;
        if (q.contains("login") || q.contains("pickup") || q.contains("pick-up")
                || q.contains("pick up") || q.contains("inbound")) direction = "LOGIN";
        else if (q.contains("logout") || q.contains("drop") || q.contains("outbound")) direction = "LOGOUT";

        String product = null;
        if (q.matches(".*\\bbus\\b.*")) product = "BUS";
        else if (q.matches(".*\\bcab\\b.*") || q.matches(".*\\bcabs\\b.*")) product = "CAB";

        return new Filters(band, direction, product, null, null);
    }

    private Plan parse(String reply, String question) {
        String[] lines = reply.trim().split("\\R");
        String head = lines[0].trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        for (Tool t : Tool.values()) {
            if (head.contains(t.name())) {
                // Always try to name a metric, not only for METRIC_WITH_CONTEXT: a
                // compound question ("are we over budget AND should we renegotiate")
                // routes to SLA_COMPLIANCE but still needs the cost figure attached,
                // otherwise half the question is silently dropped.
                String metric = lines.length > 1 && t == Tool.METRIC_WITH_CONTEXT
                        ? lines[1].trim().toLowerCase(Locale.ROOT)
                        : guessMetric(question);
                return new Plan(t, metric, "model routed to " + t, "LLM");
            }
        }
        return null;   // unparseable — fall through to the keyword floor
    }

    // ---- the deterministic floor -------------------------------------------------

    private static final List<String> SLA_CUES = List.of(
            "sla", "contract", "signed", "penalt", "compliance", "missed the", "breach of",
            "renegotiat", "invoice", "billing", "exposure");
    private static final List<String> SHIFT_CUES = List.of(
            "my team", "my people", "our team", "who was late", "did not show", "no-show",
            "no show", "did everyone", "my shift", "boarded", "roster");
    private static final List<String> REPEAT_CUES = List.of(
            "consistent", "consistently", "every week", "each week", "repeatedly",
            "always", "keeps ", "recurring", "chronic", "habitual", "pattern",
            "week after week", "persistent");
    private static final List<String> OTA_CUES = List.of(
            "ota", "on-time", "on time", "late", "delay", "punctual");

    Plan keywordPlan(String question) {
        String q = question.toLowerCase(Locale.ROOT);

        // Order matters: SLA and shift questions are checked BEFORE the OTA cues, because
        // "which vendors missed the SLA" and "who on my team was late" both mention
        // lateness but are not requests for an OTA decomposition.
        // "consistent", "always", "every week", "keeps" — a pattern question, which the
        // prior-period comparison cannot answer: it only ever sees one window against one.
        // A penalty question is about MONEY on the invoice, not about the OTA that caused
        // it — and penalties are dated by billing cycle, so they need their own query.
        if (q.contains("penalt")) {
            return new Plan(Tool.VENDOR_PENALTIES, null, "asks who is charged penalties",
                    "KEYWORD");
        }
        if (REPEAT_CUES.stream().anyMatch(q::contains)) {
            return new Plan(Tool.REPEAT_OFFENDERS, null, "asks about a recurring pattern",
                    "KEYWORD");
        }
        if (SLA_CUES.stream().anyMatch(q::contains)) {
            // "are we over budget and should we renegotiate" is one question with two
            // halves. Route it to compliance (who to renegotiate with) and let the caller
            // also attach the cost metric, so the budget half is not silently dropped.
            String m = q.contains("budget") || q.contains("cost") || q.contains("spend")
                    ? "cost_per_trip" : null;
            return new Plan(Tool.SLA_COMPLIANCE, m, "contract/penalty language", "KEYWORD");
        }
        if (SHIFT_CUES.stream().anyMatch(q::contains)) {
            return new Plan(Tool.SHIFT_READINESS, null, "team/roster language", "KEYWORD");
        }
        String metric = guessMetric(q);
        if (metric != null && !metric.equals("ota")) {
            return new Plan(Tool.METRIC_WITH_CONTEXT, metric, "names a specific metric", "KEYWORD");
        }
        if (OTA_CUES.stream().anyMatch(q::contains)) {
            return new Plan(Tool.OTA_ROOT_CAUSE, null, "asks about on-time arrival", "KEYWORD");
        }
        return new Plan(Tool.DEGRADING_METRICS, null, "general 'what is wrong'", "KEYWORD");
    }

    /** Map plain words to a registered metric id. Null when the question names none. */
    static String guessMetric(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("cost") || q.contains("spend") || q.contains("budget")) return "cost_per_trip";
        if (q.contains("no-show") || q.contains("no show")) return "no_show_rate";
        if (q.contains("safety") || q.contains("incident")) return "safety_alerts_per_1k";
        if (q.contains("rating") || q.contains("experience") || q.contains("satisf")) return "experience";
        if (q.contains("utilisation") || q.contains("utilization") || q.contains("seat")) return "seat_utilisation";
        if (q.contains("ev ") || q.contains("electric") || q.contains("esg")) return "ev_share";
        if (q.contains("penalt")) return "penalty_exposure";
        if (q.contains("ota") || q.contains("on-time") || q.contains("on time")) return "ota";
        return null;
    }
}
