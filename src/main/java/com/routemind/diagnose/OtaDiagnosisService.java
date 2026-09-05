package com.routemind.diagnose;

import com.routemind.diagnose.OtaRootCauseService.Diagnosis;
import com.routemind.diagnose.OtaRootCauseService.Driver;
import com.routemind.diagnose.OtaRootCauseService.ReasonShift;
import com.routemind.llm.LlmChat;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * "Why is OTA down?" — answered TWO ways, side by side, on purpose.
 *
 * The brief asks the system to reason and act, and this is the sharp end of it. But an
 * agentic answer to an open question is exactly where a demo can quietly start inventing
 * things, so the answer is deliberately split into two tracks the viewer can compare:
 *
 *   RULE-BASED   a deterministic shift-share decomposition. Every number is arithmetic on
 *                the trip table, every driver carries the SQL behind it, and the same input
 *                always gives the same answer. This is the track you can defend in a
 *                contract meeting.
 *
 *   AI           an LLM narrative written over the RULE-BASED numbers and nothing else. It
 *                reads more naturally and can connect the dots, but it computes nothing —
 *                it is handed the decomposition and told to explain it without introducing
 *                a figure that is not there. If the model is unavailable it falls back to
 *                the rule-based headlines, so the endpoint always answers.
 *
 * Showing both is the point. The rule-based track proves the AI track is not hallucinating,
 * and the AI track makes the rule-based track readable. Neither alone is the product.
 */
@Service
public class OtaDiagnosisService {

    private static final String SYSTEM = """
            You are a transport operations analyst. You will be given a DETERMINISTIC
            decomposition of a change in on-time arrival (OTA) — totals, the biggest drivers
            by direction, shift band, office and vendor, and how the recorded cause mix
            shifted. Write a short, plain explanation for a facilities head of WHY OTA moved
            and WHAT to look at.

            HARD RULES:
            - Use ONLY the numbers given. Do not invent or estimate any figure.
            - Every claim must trace to a driver in the data. If the data does not support a
              cause, say the data does not show one.
            - Distinguish controllable (driver) from uncontrollable (traffic, employee) delay.
            - 4 sentences maximum. No preamble, no bullet points.
            - If an attribution is flagged unconfirmed, keep that caveat.
            """;

    private final OtaRootCauseService rootCause;
    private final LlmChat llm;

    public OtaDiagnosisService(OtaRootCauseService rootCause, LlmChat llm) {
        this.rootCause = rootCause;
        this.llm = llm;
    }

    /** Both tracks, plus the shared facts they were both built from. */
    public record DualAnswer(String question,
                             Diagnosis facts,
                             Track ruleBased,
                             Track ai) {}

    public record Track(String source,          // RULES | LLM | LLM-UNAVAILABLE
                        String explanation,
                        boolean deterministic,
                        String note) {}

    public DualAnswer diagnose(java.time.LocalDate from, java.time.LocalDate to,
                               String businessUnit, int defaultWindow) {
        Diagnosis d = rootCause.diagnose(from, to, businessUnit, defaultWindow);

        Track ruleBased = new Track(
                "RULES",
                String.join(" ", d.headlines()),
                true,
                "Shift-share decomposition of the OTA change. Reproducible; every driver "
                        + "carries its own SQL.");

        Track ai = aiTrack(d);

        return new DualAnswer(
                "Why did on-time arrival change between " + d.priorStart() + ".." + d.priorEnd()
                        + " and " + d.periodStart() + ".." + d.periodEnd() + "?",
                d, ruleBased, ai);
    }

    private Track aiTrack(Diagnosis d) {
        Optional<String> narrative = llm.ask(SYSTEM, factSheet(d), 260);
        if (narrative.isPresent()) {
            return new Track("LLM", narrative.get(), false,
                    "Written by the model over the rule-based numbers only. Compare it "
                            + "against the rule-based track — it must not contain a figure "
                            + "the decomposition does not.");
        }
        // No key, or the call failed. The endpoint still answers, and says which track it is.
        return new Track("LLM-UNAVAILABLE",
                String.join(" ", d.headlines()),
                false,
                "No model configured, so this mirrors the rule-based track. Set "
                        + "routemind.narrative.sarvam.* to enable the AI narrative.");
    }

    /**
     * The exact numbers handed to the model — the ONLY thing it is allowed to reason over.
     * Kept compact and labelled so a hallucinated figure would stand out immediately against
     * it.
     */
    private String factSheet(Diagnosis d) {
        StringBuilder s = new StringBuilder();
        s.append("OTA change: ").append(fmtPts(d.otaChange()))
         .append(" (from ").append(d.otaPrev()).append("% to ").append(d.otaNow())
         .append("%). Trips: ").append(d.tripsNow()).append(" now vs ")
         .append(d.tripsPrev()).append(" prior.\n\n");

        appendDrivers(s, "By direction", d.byDirection());
        appendDrivers(s, "By shift band", d.byShiftBand());
        appendDrivers(s, "By office", d.byOffice());
        appendDrivers(s, "By vendor", d.byVendor());

        s.append("\nCause mix of late trips (share now vs prior):\n");
        for (ReasonShift r : d.reasonMix()) {
            s.append("  - ").append(r.reason()).append(": ")
             .append(r.sharePrev()).append("% -> ").append(r.shareNow()).append("% (")
             .append(fmtPts(r.changePts())).append(")")
             .append(r.controllable() ? "  [vendor-controllable, attribution unconfirmed]" : "")
             .append('\n');
        }
        return s.toString();
    }

    private void appendDrivers(StringBuilder s, String title, List<Driver> drivers) {
        s.append(title).append(" (contribution to the OTA change, points):\n");
        drivers.stream().limit(4).forEach(dr -> s.append("  - ").append(dr.value())
                .append(": ").append(fmtPts(dr.contributionPts()))
                .append("  (OTA ").append(dr.otaPrev()).append("% -> ").append(dr.otaNow())
                .append("%, ").append(dr.tripsNow()).append(" trips)\n"));
        s.append('\n');
    }

    private static String fmtPts(double v) {
        return String.format("%+.1f pts", v);
    }
}
