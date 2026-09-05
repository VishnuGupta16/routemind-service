package com.routemind.chat;

import com.routemind.diagnose.MetricDegradationService;
import com.routemind.diagnose.MetricDegradationService.Signal;
import com.routemind.diagnose.OtaDiagnosisService;
import com.routemind.diagnose.OtaDiagnosisService.DualAnswer;
import com.routemind.rules.RuleSetProperties;
import com.routemind.sla.SlaComplianceService;
import com.routemind.sla.SlaComplianceService.ComplianceRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The QA chatbot brain.
 *
 * It answers "why is X bad / down?" the way the whole system does: the numbers come from the
 * Java services, and the model only decides WHO is asking and turns the facts into prose.
 * The flow is deliberately fixed rather than agentic —
 *
 *     1. classify the persona from the question
 *     2. call the diagnosis services to find the BAD METRICS and the REASON behind each
 *        (this is the "trigger the right API" step — real analysis, not the model guessing)
 *     3. hand those facts, plus the persona's own prompt, to the model to FORMAT the answer
 *        in the fixed structure below
 *     4. if the model is unavailable, build the same structure deterministically
 *
 * Because step 2 is real service calls, the chatbot cannot fabricate a metric or a cause: it
 * can only phrase what the services found. The persona from step 1 decides the framing and
 * which action is appropriate. Same guarantee as the dual-track diagnosis, wrapped in chat.
 */
@Service
public class QaChatbotService {

    private final PersonaClassifier classifier;
    private final MetricDegradationService degradation;
    private final OtaDiagnosisService otaDiagnosis;
    private final SlaComplianceService compliance;
    private final ChatModelClient model;
    private final RuleSetProperties rules;
    private final NamedParameterJdbcTemplate jdbc;

    private final int defaultLookback;
    private final LocalDate asOf;

    public QaChatbotService(PersonaClassifier classifier, MetricDegradationService degradation,
                            OtaDiagnosisService otaDiagnosis, SlaComplianceService compliance,
                            ChatModelClient model, RuleSetProperties rules,
                            NamedParameterJdbcTemplate jdbc,
                            @Value("${routemind.chat.default-lookback-days:30}") int lookback,
                            @Value("${routemind.chat.as-of:}") String asOfStr) {
        this.classifier = classifier;
        this.degradation = degradation;
        this.otaDiagnosis = otaDiagnosis;
        this.compliance = compliance;
        this.model = model;
        this.rules = rules;
        this.jdbc = jdbc;
        this.defaultLookback = lookback;
        this.asOf = asOfStr == null || asOfStr.isBlank()
                ? LocalDate.now() : LocalDate.parse(asOfStr);
    }

    public record ChatAnswer(String question,
                             String persona,
                             String personaSource,
                             String personaRationale,
                             LocalDate from,
                             LocalDate to,
                             String businessUnit,
                             String answer,          // the formatted, persona-shaped reply
                             String answerSource,     // LLM | RULES
                             List<Section> structured, // the same content, machine-readable
                             List<Fact> facts) {}      // every number, with its reference

    public record Section(String title, List<String> lines) {}

    public record Fact(String metric, String slice, String value, String reference,
                       String verdict) {}

    // -------------------------------------------------------------------- entry

    public ChatAnswer ask(String question, String businessUnit, LocalDate from, LocalDate to) {
        PersonaClassifier.Result persona = classifier.classify(question);

        LocalDate end = to != null ? to : asOf;
        LocalDate start = from != null ? from : end.minusDays(defaultLookback - 1L);

        // ---- step 2: the real analysis. Find the bad metrics and their reasons.
        List<Signal> degrading = degradation.degrading(start, end, businessUnit);

        // OTA gets its full decomposition whenever it is degrading or the question asks for
        // it — it is the metric people ask "why is it down" about.
        boolean wantsOta = degrading.stream().anyMatch(s -> s.metricId().equals("ota"))
                || mentionsOta(question);
        DualAnswer otaWhy = wantsOta
                ? otaDiagnosis.diagnose(start, end, businessUnit,
                    rules.getSla().getOtaWindowMinutes())
                : null;

        // the facilities head also cares about who missed the contract they signed
        List<ComplianceRow> breaches = persona.personaCode().equals("FACILITIES_HEAD")
                ? compliance.compliance(start, end, businessUnit, "vendor",
                    rules.getSla().getOtaWindowMinutes(), 500).stream()
                    .filter(c -> !"MET".equals(c.status())).toList()
                : List.of();

        List<Fact> facts = collectFacts(degrading, otaWhy, breaches);
        List<Section> structured = buildStructure(persona.personaCode(), question,
                degrading, otaWhy, breaches);

        // ---- step 3: format. Model if available, deterministic template otherwise.
        String answer;
        String source;
        String llm = formatWithModel(question, persona.personaCode(), structured);
        if (llm != null) {
            answer = llm;
            source = "LLM";
        } else {
            answer = renderDeterministic(structured);
            source = "RULES";
        }

        return new ChatAnswer(question, persona.personaCode(), persona.source(),
                persona.rationale(), start, end, businessUnit, answer, source,
                structured, facts);
    }

    // ------------------------------------------------------------------ structure

    /**
     * The fixed answer shape — the "particular format" every reply follows:
     *   ANSWER · WHAT'S WRONG · WHY · RECOMMENDED ACTION · EVIDENCE
     * The persona changes the wording and which action is appropriate, not the sections.
     */
    private List<Section> buildStructure(String persona, String question,
                                         List<Signal> degrading, DualAnswer otaWhy,
                                         List<ComplianceRow> breaches) {
        List<Section> out = new ArrayList<>();

        // ANSWER — the one-line direct reply
        out.add(new Section("ANSWER", List.of(directAnswer(persona, degrading, otaWhy))));

        // WHAT'S WRONG — the bad metrics, with shape
        List<String> wrong = new ArrayList<>();
        if (degrading.isEmpty()) {
            wrong.add("Nothing is degrading in this window — every tracked metric is stable "
                    + "or improving.");
        } else {
            for (Signal s : degrading) {
                wrong.add("%s — %s (%s). %s".formatted(s.displayName(), s.shape(),
                        s.status(), s.reason()));
            }
        }
        out.add(new Section("WHAT'S WRONG", wrong));

        // WHY — root cause for the headline metric
        if (otaWhy != null) {
            List<String> why = new ArrayList<>(otaWhy.facts().headlines());
            why.add("Rule-based: " + otaWhy.ruleBased().explanation());
            if (!"LLM-UNAVAILABLE".equals(otaWhy.ai().source())) {
                why.add("AI read: " + otaWhy.ai().explanation());
            }
            out.add(new Section("WHY", why));
        }

        // RECOMMENDED ACTION — persona-appropriate
        out.add(new Section("RECOMMENDED ACTION",
                List.of(action(persona, degrading, otaWhy, breaches))));

        // EVIDENCE — the numbers with references
        List<String> ev = new ArrayList<>();
        for (Signal s : degrading) {
            ev.add("%s: %s vs target %s%s".formatted(s.displayName(),
                    num(s.latest(), s.unit()), num(s.target(), s.unit()),
                    s.worstSlice() == null ? ""
                            : " · largest contributor " + s.worstSlice()));
        }
        for (ComplianceRow c : breaches) {
            ev.add("%s: %.1f%% vs %.1f%% SLA (%s) over %,d trips — %s".formatted(
                    c.vendor(), c.otaPct(), c.target(), c.slaName(), c.trips(), c.status()));
        }
        if (ev.isEmpty()) ev.add("No breaching numbers this window.");
        out.add(new Section("EVIDENCE", ev));

        return out;
    }

    private String directAnswer(String persona, List<Signal> degrading, DualAnswer otaWhy) {
        if (otaWhy != null && otaWhy.facts().declined()) {
            return "On-time arrival fell %.1f points (%.1f%% to %.1f%%). %s".formatted(
                    Math.abs(otaWhy.facts().otaChange()), otaWhy.facts().otaPrev(),
                    otaWhy.facts().otaNow(),
                    degrading.isEmpty() ? "" : degrading.size() + " metric(s) degrading in total.");
        }
        if (degrading.isEmpty()) {
            return "Nothing is degrading in this window — the operation is holding.";
        }
        Signal top = degrading.get(0);
        return "%d metric(s) are degrading; the most urgent is %s, which %s.".formatted(
                degrading.size(), top.displayName(),
                top.shape() == MetricDegradationService.Shape.SUDDEN
                        ? "stepped down suddenly" : "is sliding");
    }

    private String action(String persona, List<Signal> degrading, DualAnswer otaWhy,
                          List<ComplianceRow> breaches) {
        if (degrading.isEmpty() && breaches.isEmpty()) {
            return "No action needed this window.";
        }
        return switch (persona) {
            case "FACILITIES_HEAD" -> breaches.isEmpty()
                    ? "Review the degrading metrics against contract terms at the next vendor "
                      + "review; nothing requires an immediate escalation."
                    : "Raise %s at the next contract review — %.1f%% against the %.1f%% they "
                      .formatted(breaches.get(0).vendor(), breaches.get(0).otaPct(),
                              breaches.get(0).target())
                      + "signed. Options in your gift: a penalty claim, or moving volume to a "
                      + "compliant vendor.";
            case "LINE_MANAGER" -> "Check your team's morning pickups on the affected shifts "
                    + "and flag repeat late riders to the transport desk.";
            default -> {   // TRANSPORT_MANAGER
                Signal top = degrading.isEmpty() ? null : degrading.get(0);
                if (top == null) yield "Monitor; nothing needs chasing yet.";
                String slice = top.worstSlice() == null ? "the driving slice" : top.worstSlice();
                yield top.shape() == MetricDegradationService.Shape.SUDDEN
                        ? "Chase %s now — it broke, not drifted. Check %s for a same-day cause."
                          .formatted(top.displayName(), slice)
                        : "Get ahead of %s while it is a trend — led by %s.".formatted(
                                top.displayName(), slice);
            }
        };
    }

    // -------------------------------------------------------------------- format

    private String formatWithModel(String question, String persona, List<Section> structured) {
        String personaPrompt = personaPrompt(persona);
        StringBuilder facts = new StringBuilder();
        for (Section s : structured) {
            facts.append(s.title()).append(":\n");
            for (String line : s.lines()) facts.append("  - ").append(line).append('\n');
        }

        String prompt = """
                %s

                A user asked: "%s"

                Below is the analysis produced by the system. Write the reply to the user.
                HARD RULES:
                - Use ONLY the facts below. Do not invent or change any number.
                - Keep the section order: ANSWER, WHAT'S WRONG, WHY, RECOMMENDED ACTION, EVIDENCE.
                - Be concise and in this persona's voice.
                - If a section is empty, omit it.

                ANALYSIS:
                %s
                """.formatted(personaPrompt, question, facts);

        return model.ask(prompt).orElse(null);
    }

    /** The persona's own prompt, from the DB, so the voice can be tuned without a redeploy. */
    private String personaPrompt(String personaCode) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT prompt_template FROM persona WHERE code = :c AND prompt_template IS NOT NULL",
                    new MapSqlParameterSource("c", personaCode), String.class);
            if (!rows.isEmpty() && rows.get(0) != null) return rows.get(0);
        } catch (Exception ignored) {
            // no DB (unit test) or no prompt — fall through to a sane default
        }
        return "You are a transport operations assistant answering for the " + personaCode
                + " persona.";
    }

    private String renderDeterministic(List<Section> structured) {
        StringBuilder s = new StringBuilder();
        for (Section sec : structured) {
            if (sec.lines().isEmpty()) continue;
            s.append(sec.title()).append('\n');
            for (String line : sec.lines()) s.append("  • ").append(line).append('\n');
            s.append('\n');
        }
        return s.toString().trim();
    }

    // ------------------------------------------------------------------ helpers

    private List<Fact> collectFacts(List<Signal> degrading, DualAnswer otaWhy,
                                    List<ComplianceRow> breaches) {
        List<Fact> facts = new ArrayList<>();
        for (Signal s : degrading) {
            facts.add(new Fact(s.metricId(), s.worstSlice(),
                    num(s.latest(), s.unit()), "target " + num(s.target(), s.unit()),
                    s.status()));
        }
        if (otaWhy != null) {
            otaWhy.facts().byDirection().forEach(d -> facts.add(new Fact(
                    "ota", d.value(), d.otaNow() + "%",
                    "prior " + d.otaPrev() + "% (contribution " + d.contributionPts() + " pts)",
                    d.contributionPts() < 0 ? "DROVE_DOWN" : "HELD")));
        }
        for (ComplianceRow c : breaches) {
            facts.add(new Fact("ota", c.vendor(), c.otaPct() + "%",
                    c.slaName() + " " + c.target() + "%", c.status()));
        }
        return facts;
    }

    private boolean mentionsOta(String q) {
        String l = q.toLowerCase();
        return l.contains("ota") || l.contains("on-time") || l.contains("on time")
                || l.contains("punctual") || l.contains("late") || l.contains("delay");
    }

    private static String num(double v, String unit) {
        return switch (unit == null ? "" : unit) {
            case "percent" -> String.format("%.1f%%", v);
            case "currency" -> String.format("₹%,.0f", v);
            case "rating" -> String.format("%.2f", v);
            default -> String.format("%,.1f", v);
        };
    }
}
