package com.routemind.chat;

import com.routemind.diagnose.MetricDegradationService;
import com.routemind.llm.LlmChat;
import com.routemind.llm.LlmTrace;
import com.routemind.metrics.MetricService;
import com.routemind.metrics.PeerComparisonService;
import com.routemind.metrics.PeerComparisonService.PeerComparison;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.persona.ShiftReadinessService;
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
import java.util.Map;

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
    private final QueryPlanner planner;
    private final SafeSqlExecutor safeSql;
    private final MetricService metrics;
    private final PeerComparisonService peers;
    private final LlmTrace trace;
    private final SlicedMetricService sliced;
    private final ShiftReadinessService shifts;
    private final MetricDegradationService degradation;
    private final OtaDiagnosisService otaDiagnosis;
    private final SlaComplianceService compliance;
    private final LlmChat model;
    private final RuleSetProperties rules;
    private final NamedParameterJdbcTemplate jdbc;

    private final int defaultLookback;
    private final LocalDate asOf;

    public QaChatbotService(PersonaClassifier classifier, QueryPlanner planner,
                            SafeSqlExecutor safeSql,
                            MetricDegradationService degradation,
                            OtaDiagnosisService otaDiagnosis, SlaComplianceService compliance,
                            MetricService metrics, PeerComparisonService peers,
                            ShiftReadinessService shifts, LlmTrace trace,
                            SlicedMetricService sliced,
                            LlmChat model, RuleSetProperties rules,
                            NamedParameterJdbcTemplate jdbc,
                            @Value("${routemind.chat.default-lookback-days:30}") int lookback,
                            @Value("${routemind.chat.as-of:}") String asOfStr) {
        this.classifier = classifier;
        this.planner = planner;
        this.safeSql = safeSql;
        this.metrics = metrics;
        this.peers = peers;
        this.shifts = shifts;
        this.trace = trace;
        this.sliced = sliced;
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
                             String tool,             // which service answered it
                             String toolRationale,    // why that tool was chosen
                             String sqlUsed,          // the bounded query, when SQL was needed
                             List<LlmTrace.Call> llmCalls,  // every model call this answer made
                             List<Section> structured, // the same content, machine-readable
                             List<Fact> facts) {}      // every number, with its reference

    public record Section(String title, List<String> lines) {}

    public record Fact(String metric, String slice, String value, String reference,
                       String verdict) {}

    // -------------------------------------------------------------------- entry

    public ChatAnswer ask(String question, String businessUnit, LocalDate from, LocalDate to) {
        trace.begin();   // collect every model call this one question causes
        PersonaClassifier.Result persona = classifier.classify(question);

        LocalDate end = to != null ? to : asOf;
        LocalDate start = from != null ? from : end.minusDays(defaultLookback - 1L);

        // ---- step 2: choose the tool, THEN run only what it asked for.
        // Previously every question ran the same OTA decomposition, so asking about cost or
        // about one team came back with OTA-by-direction. The plan is what makes the answer
        // match the question.
        QueryPlanner.Plan plan = planner.plan(question);

        // "what is wrong" always needs the degradation scan; the other tools do not, and
        // running it anyway is what produced the "nothing is degrading" contradiction inside
        // an answer that then went on to explain a 2-point fall.
        boolean wantsScan = plan.tool() == QueryPlanner.Tool.DEGRADING_METRICS;
        List<Signal> degrading = wantsScan
                ? degradation.degrading(start, end, businessUnit)
                : List.of();

        DualAnswer otaWhy = plan.tool() == QueryPlanner.Tool.OTA_ROOT_CAUSE
                ? otaDiagnosis.diagnose(start, end, businessUnit,
                    rules.getSla().getOtaWindowMinutes())
                : null;

        // One named metric, with its target, prior period and top contributors.
        // A plan may name a metric alongside its tool — "are we over budget AND should we
        // renegotiate" needs the cost figure as well as the vendor scorecard, so the metric
        // is fetched whenever the plan carries one.
        MetricWithContext single = plan.metricId() == null ? null
                : metrics.metric(plan.metricId(), start, end, businessUnit).orElse(null);

        // The question named a slice ("on the night shift", "for BUS"), so the headline
        // number must be for that slice. Answering with the all-trips figure would be a
        // wrong answer with correct arithmetic behind it.
        SlicedMetricService.Sliced slice = plan.filters().any()
                ? sliced.otaForSlice(plan.filters(), start, end, businessUnit,
                        rules.getSla().getOtaWindowMinutes(),
                        rules.getTargets().getOrDefault("ota", 95.0)).orElse(null)
                : null;

        // The benchmark behind the comparison. A verdict of "below target" invites the
        // question "compared with what?" — so whenever we judge a metric, we also say how
        // the peer group is doing on it, and where this subject sits in that spread.
        PeerComparison benchmark = null;
        if (single != null) {
            try {
                benchmark = peers.acrossBusinessUnits(single.metric(), start, end, businessUnit);
            } catch (Exception e) {
                benchmark = null;   // a missing benchmark must never fail the answer
            }
        }

        // Per-shift readiness for the team-level question.
        List<ShiftReadinessService.ShiftRow> shiftRows =
                plan.tool() == QueryPlanner.Tool.SHIFT_READINESS
                        ? shifts.forDay(end, businessUnit, rules.getSla().getOtaWindowMinutes())
                        : List.of();

        // Who misses the target week after week. Answered by the same query the
        // /api/slice/repeat-offenders endpoint serves, so chat and API cannot disagree.
        List<Map<String, Object>> repeats =
                plan.tool() == QueryPlanner.Tool.REPEAT_OFFENDERS
                        ? repeatOffenders(start, end, businessUnit)
                        : List.of();

        // Who carries the penalties. Same query the /api/slice/penalties endpoint serves.
        List<Map<String, Object>> penalties =
                plan.tool() == QueryPlanner.Tool.VENDOR_PENALTIES
                        ? vendorPenalties(start, end, businessUnit)
                        : List.of();

        // SQL is the LAST resort and is bounded to the already-filtered slice.
        SafeSqlExecutor.SqlResult sql = plan.tool() == QueryPlanner.Tool.SQL_FALLBACK
                ? safeSql.run(question, start, end, businessUnit)
                : null;

        // the contract view is driven by the QUESTION now, not only by the persona
        List<ComplianceRow> breaches = plan.tool() == QueryPlanner.Tool.SLA_COMPLIANCE
                || persona.personaCode().equals("FACILITIES_HEAD")
                ? compliance.compliance(start, end, businessUnit, "vendor",
                    rules.getSla().getOtaWindowMinutes(), 500).stream()
                    .filter(c -> !"MET".equals(c.status())).toList()
                : List.of();

        List<Fact> facts = collectFacts(degrading, otaWhy, breaches);
        addPlanFacts(facts, single, shiftRows, sql);
        addBenchmarkFacts(facts, benchmark);
        addSliceFacts(facts, slice);
        List<Section> structured = buildStructure(persona.personaCode(), question,
                degrading, otaWhy, breaches);
        addPlanSections(structured, single, shiftRows, sql);
        addRepeatFacts(facts, structured, repeats);
        addPenaltyFacts(facts, structured, penalties);
        addSliceSection(structured, slice, plan.filters());
        addBenchmarkSection(structured, benchmark);

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
                plan.tool().name(), plan.rationale(),
                sql == null ? null : sql.sql(),
                trace.end(),
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
        out.add(new Section("ANSWER",
                List.of(directAnswer(persona, degrading, otaWhy, breaches))));

        // WHAT'S WRONG — the bad metrics, with shape
        List<String> wrong = new ArrayList<>();
        if (degrading.isEmpty()) {
            // Only claim "nothing is degrading" when a full scan actually ran. A targeted
            // question runs one service, and asserting a clean bill of health off the back
            // of it contradicted the very drop the same answer went on to explain.
            wrong.add(otaWhy != null || !breaches.isEmpty()
                    ? "See WHY below — this answer is scoped to what was asked, not a full scan."
                    : "Nothing is degrading in this window — every tracked metric is stable "
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

    private String directAnswer(String persona, List<Signal> degrading, DualAnswer otaWhy,
                                List<ComplianceRow> breaches) {
        // A yes/no question deserves a yes/no first word. Contract breaches are checked
        // BEFORE the degradation scan, because "should we renegotiate?" is answered by who
        // is under contract, not by whether a metric moved this window — a vendor can sit
        // below the SLA it signed all year without that being a new "degradation".
        if (!breaches.isEmpty()) {
            ComplianceRow worst = breaches.get(0);
            long hard = breaches.stream().filter(b -> "BREACH".equals(b.status())).count();
            return "Yes — %d vendor(s) are below the SLA they signed%s. Start with %s at %.1f%% against %.1f%% over %,d trips."
                    .formatted(breaches.size(),
                            hard > 0 ? " (" + hard + " in outright breach)" : " (all at risk, none in outright breach)",
                            worst.vendor(), worst.otaPct(), worst.target(), worst.trips());
        }
        if (otaWhy != null && otaWhy.facts().declined()) {
            return "On-time arrival fell %.1f points (%.1f%% to %.1f%%). %s".formatted(
                    Math.abs(otaWhy.facts().otaChange()), otaWhy.facts().otaPrev(),
                    otaWhy.facts().otaNow(),
                    degrading.isEmpty() ? "" : degrading.size() + " metric(s) degrading in total.");
        }
        if (degrading.isEmpty()) {
            return "No — nothing is outside its reference in this window; the operation is "
                 + "holding and no vendor is below the SLA it signed.";
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

    /**
     * Rewrite ONLY the direct answer in the persona's voice.
     *
     * This used to hand the model the whole analysis and ask it to re-emit every section.
     * That is a large restructuring job, and the model reasoned past 8,700 tokens without
     * producing anything — so the answer always fell back to the template, slowly. Worse,
     * when it did answer it padded with hypotheticals and arithmetic of its own.
     *
     * The deterministic sections are already correct and already carry their references;
     * they do not need an LLM. The one thing a model genuinely adds is phrasing the direct
     * answer the way this persona would want to hear it, so that is the only thing asked
     * for — a small, bounded job it can actually finish.
     */
    private String formatWithModel(String question, String persona, List<Section> structured) {
        Section answer = structured.stream()
                .filter(sec -> sec.title().equals("ANSWER"))
                .findFirst().orElse(null);
        if (answer == null || answer.lines().isEmpty()) return null;

        String facts = structured.stream()
                .flatMap(sec -> sec.lines().stream())
                .limit(12)
                .collect(java.util.stream.Collectors.joining("\n  - ", "  - ", ""));

        String prompt = """
                %s

                The user asked: "%s"

                Our analysis found:
                %s

                Write ONE sentence answering the question, in this persona's voice.
                RULES:
                - Use only the numbers above, exactly as written. Invent nothing.
                - No hypotheticals, no arithmetic of your own, no recalculated targets.
                - If the analysis does not answer the question, reply exactly:
                  "Still working on it — our data doesn't cover that yet."
                - One sentence. No preamble, no bullet, no section heading.
                """.formatted(personaPrompt(persona), question, facts);

        String line = model.ask("You write one-sentence operational answers.",
                prompt, 200, "chat-answer").orElse(null);
        if (line == null || line.isBlank()) return null;

        // Substitute the persona-voiced line into the deterministic structure, so the
        // sections, their order and their evidence stay exactly as computed.
        List<Section> merged = new ArrayList<>(structured);
        merged.replaceAll(sec -> sec.title().equals("ANSWER")
                ? new Section("ANSWER", List.of(line.trim()))
                : sec);
        return renderDeterministic(merged);
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

    // ------------------------------------------------- facts from the chosen tool

    /**
     * Facts produced by the tool the planner picked. Kept separate from {@link #collectFacts}
     * so the evidence always traces back to the service that was actually called — a fact
     * with no reference is what "not actionable" looks like to the caller.
     */
    private void addPlanFacts(List<Fact> facts, MetricWithContext m,
                              List<ShiftReadinessService.ShiftRow> shiftRows,
                              SafeSqlExecutor.SqlResult sql) {
        if (m != null) {
            facts.add(new Fact(m.metric(), null, num(m.value(), m.unit()),
                    "target " + num(m.target(), m.unit())
                            + (m.priorValue() == null ? ""
                               : " · prior " + num(m.priorValue(), m.unit())),
                    String.valueOf(m.status())));
            if (m.topContributors() != null) {
                m.topContributors().stream().limit(3).forEach(c ->
                        facts.add(new Fact(m.metric(), c.member(), c.pct() + "%",
                                "share of the " + m.attributionDimension() + " impact",
                                "CONTRIBUTOR")));
            }
        }
        // Worst shifts first — a line manager acts on the floor that is short, not the average.
        shiftRows.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        ShiftReadinessService.ShiftRow::readinessPct))
                .limit(5)
                .forEach(r -> facts.add(new Fact("shift_readiness",
                        r.shift() + " @ " + r.office(),
                        "%.1f%%".formatted(r.readinessPct()),
                        "%d expected, %d no-shows".formatted(r.employeesExpected(), r.noShows()),
                        r.readinessPct() < 85 ? "SHORT" : "OK")));

        if (sql != null && sql.ok()) {
            sql.rows().stream().limit(10).forEach(row ->
                    facts.add(new Fact("sql", String.valueOf(row.values().stream().findFirst()
                            .orElse("row")), row.toString(), "bounded read", "SQL")));
        }
    }

    /** The same content in the fixed section shape, so the prose has something to render. */
    private void addPlanSections(List<Section> out, MetricWithContext m,
                                 List<ShiftReadinessService.ShiftRow> shiftRows,
                                 SafeSqlExecutor.SqlResult sql) {
        if (m != null) {
            List<String> lines = new ArrayList<>();
            lines.add(m.headline());
            out.add(new Section("METRIC", lines));
        }
        if (!shiftRows.isEmpty()) {
            List<String> lines = new ArrayList<>();
            shiftRows.stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            ShiftReadinessService.ShiftRow::readinessPct))
                    .limit(5)
                    .forEach(r -> lines.add("%s at %s — %.1f%% ready, %d of %d absent. %s"
                            .formatted(r.shift(), r.office(), r.readinessPct(),
                                    r.noShows(), r.employeesExpected(), r.note())));
            out.add(new Section("SHIFTS", lines));
        }
        if (sql != null) {
            out.add(new Section("BOUNDED QUERY", List.of(
                    sql.ok() ? sql.note() + " — " + sql.rows().size() + " rows"
                             : "No API covered this and " + sql.note() + ".")));
        }
    }

    // ------------------------------------------------------------------ benchmark

    /**
     * The comparison set behind a verdict. "94.0% against a 95.0% target" is only half an
     * answer — whether that is bad depends on whether every peer is at 98% or at 91%.
     */
    private void addBenchmarkFacts(List<Fact> facts, PeerComparison b) {
        if (b == null || b.peerCount() < 2) return;
        facts.add(new Fact(b.metric(), "peer best", String.valueOf(b.best()),
                "best of " + b.peerCount() + " " + b.dimension() + "s", "BENCHMARK"));
        facts.add(new Fact(b.metric(), "peer median", String.valueOf(b.median()),
                "median of " + b.peerCount() + " " + b.dimension() + "s", "BENCHMARK"));
        facts.add(new Fact(b.metric(), "peer worst", String.valueOf(b.worst()),
                "worst of " + b.peerCount() + " " + b.dimension() + "s", "BENCHMARK"));
    }

    private void addBenchmarkSection(List<Section> out, PeerComparison b) {
        if (b == null || b.peerCount() < 2) return;
        List<String> lines = new ArrayList<>();
        lines.add(b.headline());
        lines.add("Peer spread across %d %ss — best %s, median %s, worst %s.".formatted(
                b.peerCount(), b.dimension(), b.best(), b.median(), b.worst()));
        if (b.subjectRank() != null && b.subject() != null) {
            lines.add("%s ranks %d of %d.".formatted(
                    b.subject(), b.subjectRank(), b.peerCount()));
        }
        out.add(new Section("BENCHMARK", lines));
    }

    // ---------------------------------------------------------------- sliced answer

    private void addSliceFacts(List<Fact> facts, SlicedMetricService.Sliced s) {
        if (s == null) return;
        // Put the slice FIRST: it is the answer to what was asked, and the all-trips
        // figures that follow are context for it, not the other way round.
        facts.add(0, new Fact(s.metricId(), s.sliceLabel(),
                "%.1f%%".formatted(s.value()),
                "target %.1f%%%s · n=%,d".formatted(s.target(),
                        s.priorValue() == null ? ""
                                : " · prior %.1f%%".formatted(s.priorValue()),
                        s.sampleSize()),
                s.verdict()));
    }

    private void addSliceSection(List<Section> out, SlicedMetricService.Sliced s,
                                 QueryPlanner.Filters f) {
        if (f == null || !f.any()) return;
        if (s == null) {
            out.add(new Section("SLICE", List.of(
                    "Still working on it — no trips matched " + f.label()
                            + " in this window, so there is nothing to report for it.")));
            return;
        }
        // Replace the ANSWER line: the slice IS the answer when one was asked for.
        out.removeIf(sec -> sec.title().equals("ANSWER"));
        out.add(0, new Section("ANSWER", List.of(s.headline())));
    }

    // ------------------------------------------------------------ repeat offenders

    /**
     * Vendors below target in at least half the weeks of the window, over at least three
     * measured weeks. The alert stream compares one window with the previous one, so it
     * cannot see a vendor that has simply always been bad — which is exactly the vendor a
     * contract conversation is about.
     */
    private List<Map<String, Object>> repeatOffenders(LocalDate from, LocalDate to,
                                                      String businessUnit) {
        double target = rules.getTargets().getOrDefault("ota", 95.0);
        return jdbc.queryForList("""
                WITH weekly AS (
                    SELECT t.vendor AS member,
                           date_trunc('week', t.trip_date) AS wk,
                           count(*) AS trips,
                           100.0 * count(*) FILTER (WHERE t.delay_minutes <= :window)
                                 / NULLIF(count(*), 0) AS ota
                    FROM trips t
                    WHERE t.trip_date BETWEEN :from AND :to
                      AND (CAST(:bu AS text) IS NULL OR t.business_unit = :bu)
                      AND t.product_type <> 'SPOT_2.0'
                    GROUP BY 1, 2
                    HAVING count(*) >= 200
                )
                SELECT member,
                       count(*) AS weeks_measured,
                       count(*) FILTER (WHERE ota < :target) AS weeks_missed,
                       round(avg(ota)::numeric, 1) AS avg_ota,
                       sum(trips) AS trips
                FROM weekly
                GROUP BY member
                HAVING count(*) >= 3
                   AND count(*) FILTER (WHERE ota < :target) * 2 >= count(*)
                ORDER BY weeks_missed DESC, avg_ota ASC
                LIMIT 10
                """, new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("bu", businessUnit)
                .addValue("window", rules.getSla().getOtaWindowMinutes())
                .addValue("target", target));
    }

    private void addRepeatFacts(List<Fact> facts, List<Section> out,
                                List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<String> lines = new java.util.ArrayList<>();
        double target = rules.getTargets().getOrDefault("ota", 95.0);
        for (Map<String, Object> r : rows) {
            String member = String.valueOf(r.get("member"));
            Object missed = r.get("weeks_missed"), measured = r.get("weeks_measured");
            Object avg = r.get("avg_ota"), trips = r.get("trips");
            lines.add("%s — missed target in %s of %s weeks, averaging %s%% over %s trips."
                    .formatted(member, missed, measured, avg, trips));
            facts.add(new Fact("ota", member, avg + "%",
                    "target %.1f%% · missed %s of %s weeks".formatted(target, missed, measured),
                    "REPEAT_OFFENDER"));
        }
        out.removeIf(sec -> sec.title().equals("ANSWER"));
        out.add(0, new Section("ANSWER", List.of(
                "Yes — %d vendor(s) miss the %.1f%% target in most weeks, not just once. Worst: %s."
                        .formatted(rows.size(), target, rows.get(0).get("member")))));
        out.add(new Section("CONSISTENT OFFENDERS", lines));
    }

    // -------------------------------------------------------------- vendor penalties

    /**
     * Penalty lines are dated by BILLING CYCLE, not trip date — a July penalty can be
     * raised in August — so the window is applied to cycle_start/cycle_end. Filtering these
     * by trip_date silently returns almost nothing, which is what made an earlier answer
     * report 0.0% penalties while nearly a million rupees sat on one contract.
     */
    private List<Map<String, Object>> vendorPenalties(LocalDate from, LocalDate to,
                                                      String businessUnit) {
        return jdbc.queryForList("""
                WITH pen AS (
                    SELECT vendor, count(*) AS lines, abs(sum(trip_cost)) AS amount
                    FROM billing
                    WHERE is_penalty
                      AND cycle_start >= :from AND cycle_end <= :to
                      AND (CAST(:bu AS text) IS NULL OR business_unit = :bu)
                    GROUP BY vendor
                )
                SELECT vendor, lines, round(amount, 0) AS amount,
                       round(100.0 * amount / NULLIF(sum(amount) OVER (), 0), 1) AS share_pct
                FROM pen ORDER BY amount DESC LIMIT 8
                """, new MapSqlParameterSource()
                .addValue("from", from).addValue("to", to)
                .addValue("bu", businessUnit));
    }

    private void addPenaltyFacts(List<Fact> facts, List<Section> out,
                                 List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<String> lines = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            lines.add("%s — ₹%s across %s penalty line(s), %s%% of all penalties."
                    .formatted(r.get("vendor"), r.get("amount"), r.get("lines"),
                            r.get("share_pct")));
            facts.add(new Fact("penalty_amount", String.valueOf(r.get("vendor")),
                    "₹" + r.get("amount"),
                    r.get("share_pct") + "% of all penalties raised in the window",
                    "PENALTY"));
        }
        Map<String, Object> top = rows.get(0);
        out.removeIf(sec -> sec.title().equals("ANSWER"));
        out.add(0, new Section("ANSWER", List.of(
                "%s carries the most — ₹%s across %s penalty line(s), %s%% of every penalty raised in this window."
                        .formatted(top.get("vendor"), top.get("amount"), top.get("lines"),
                                top.get("share_pct")))));
        out.add(new Section("PENALTIES BY VENDOR", lines));
    }
}
