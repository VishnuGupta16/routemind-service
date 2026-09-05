package com.routemind.report;

import com.routemind.diagnose.MetricDegradationService;
import com.routemind.diagnose.MetricDegradationService.Shape;
import com.routemind.diagnose.MetricDegradationService.Signal;
import com.routemind.diagnose.OtaDiagnosisService;
import com.routemind.diagnose.OtaDiagnosisService.DualAnswer;
import com.routemind.report.GeneratedReport.Fact;
import com.routemind.rules.RuleSetProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Operational signals for the Transport Manager.
 *
 * The opposite artefact to the facilities-head briefing. That one is a monthly narrative
 * ordered by budget authority; this is a same-day queue ordered by urgency. The manager
 * coordinates vendors, plans shifts and manages delays, so what they need is: which metrics
 * are degrading, whether each one BROKE (an incident to chase now) or is SLIDING (a trend to
 * get ahead of), the slice driving it, and — for OTA, the metric they live in — the full
 * decomposition of why.
 *
 * It is built to be read in thirty seconds, so it leads with the count and the single most
 * urgent signal, and every line already carries its own reason. There is no "open the
 * dashboard to find out more" — the reason is the signal.
 */
@Component
public class TransportManagerSignals implements ReportGenerator {

    private final MetricDegradationService degradation;
    private final OtaDiagnosisService otaDiagnosis;
    private final RuleSetProperties rules;

    public TransportManagerSignals(MetricDegradationService degradation,
                                   OtaDiagnosisService otaDiagnosis,
                                   RuleSetProperties rules) {
        this.degradation = degradation;
        this.otaDiagnosis = otaDiagnosis;
        this.rules = rules;
    }

    public String key() { return "transport_manager_signals"; }
    public String personaCode() { return "TRANSPORT_MANAGER"; }

    @Override
    public GeneratedReport generate(Request r) {
        List<Signal> degrading = degradation.degrading(r.periodStart(), r.periodEnd(),
                r.businessUnit());
        List<Fact> facts = new ArrayList<>();
        double severity = 0;

        for (Signal s : degrading) {
            severity += s.urgency() / 5.0;   // urgency already blends status and shape
            facts.add(Fact.of(s.metricId())
                    .on(s.worstSliceDimension(), s.worstSlice())
                    .value(s.latest(), s.unit(), null)
                    .against(s.target(), "TARGET", "target " + s.target())
                    .verdict(s.status())
                    .contribution(s.urgency())
                    .build());
        }
        severity = Math.min(100, Math.round(severity * 10) / 10.0);

        // The manager lives in OTA, so its full "why" is attached whenever it is one of the
        // degrading signals — the one metric that gets the decomposition, not just a line.
        DualAnswer otaWhy = degrading.stream().anyMatch(s -> s.metricId().equals("ota"))
                ? otaDiagnosis.diagnose(r.periodStart(), r.periodEnd(), r.businessUnit(),
                    rules.getSla().getOtaWindowMinutes())
                : null;

        boolean actionable = !degrading.isEmpty();
        return new GeneratedReport(key(), personaCode(), r.businessUnit(),
                r.periodStart(), r.periodEnd(), r.compareStart(), r.compareEnd(),
                headline(degrading), body(r, degrading, otaWhy),
                action(degrading), severity, actionable, "RULES", facts);
    }

    private String headline(List<Signal> degrading) {
        if (degrading.isEmpty()) {
            return "All metrics stable or improving — no operational signal.";
        }
        Signal top = degrading.get(0);
        long sudden = degrading.stream().filter(s -> s.shape() == Shape.SUDDEN).count();
        String lead = degrading.size() == 1 ? "1 metric is degrading"
                : degrading.size() + " metrics are degrading";
        String urgent = top.shape() == Shape.SUDDEN
                ? " — most urgent: " + top.displayName() + " stepped down suddenly"
                : " — most urgent: " + top.displayName() + " is sliding";
        String incidents = sudden > 0 ? " (" + sudden + " sudden)" : "";
        return lead + incidents + urgent + ".";
    }

    private String body(Request r, List<Signal> degrading, DualAnswer otaWhy) {
        StringBuilder s = new StringBuilder();
        s.append("Window ").append(r.periodStart()).append(" to ").append(r.periodEnd());
        if (r.businessUnit() != null) s.append(" · ").append(r.businessUnit());
        s.append("\n\n");

        if (degrading.isEmpty()) {
            s.append("Nothing is degrading. Every tracked metric is stable, improving, or "
                    + "has too little volume this window to judge.\n");
            return s.toString();
        }

        // Incidents first — a sudden step is what needs a person now.
        List<Signal> sudden = degrading.stream()
                .filter(x -> x.shape() == Shape.SUDDEN).toList();
        List<Signal> sliding = degrading.stream()
                .filter(x -> x.shape() == Shape.INCREMENTAL).toList();

        if (!sudden.isEmpty()) {
            s.append("SUDDEN — chase now\n");
            for (Signal x : sudden) s.append("  ! ").append(x.reason()).append('\n');
            s.append('\n');
        }
        if (!sliding.isEmpty()) {
            s.append("SLIDING — get ahead of\n");
            for (Signal x : sliding) s.append("  ~ ").append(x.reason()).append('\n');
            s.append('\n');
        }

        if (otaWhy != null) {
            s.append("WHY OTA MOVED (decomposition)\n");
            for (String line : otaWhy.facts().headlines()) s.append("  · ").append(line).append('\n');
            s.append("\n  Rule-based: ").append(otaWhy.ruleBased().explanation());
            if (!"LLM-UNAVAILABLE".equals(otaWhy.ai().source())) {
                s.append("\n  AI read: ").append(otaWhy.ai().explanation());
            }
            s.append('\n');
        }
        return s.toString();
    }

    /**
     * The next operational move — things a transport manager actually does: reassign a
     * shift, chase a vendor, reroute. Narrow to whatever is most urgent.
     */
    private String action(List<Signal> degrading) {
        if (degrading.isEmpty()) return "No action needed this window.";
        Signal top = degrading.get(0);
        String slice = top.worstSlice() == null ? "the driving slice"
                : top.worstSlice() + " (" + top.worstSliceDimension() + ")";
        if (top.shape() == Shape.SUDDEN) {
            return "Start with " + top.displayName() + ": it broke, not drifted. Check "
                    + slice + " for a specific cause today — a vehicle pulled, a route "
                    + "changed, a shift added — before it is baked into the monthly number.";
        }
        return "Start with " + top.displayName() + ": it is sliding, led by " + slice
                + ". Address it now while it is a trend, not after it breaches the SLA.";
    }
}
