package com.routemind.report;

import com.routemind.diagnose.MetricDegradationService;
import com.routemind.diagnose.MetricDegradationService.Signal;
import com.routemind.diagnose.OtaRootCauseService;
import com.routemind.diagnose.OtaRootCauseService.Diagnosis;
import com.routemind.diagnose.OtaRootCauseService.Driver;
import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.rules.RuleSetProperties;
import com.routemind.sla.SlaComplianceService;
import com.routemind.sla.SlaComplianceService.ComplianceRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The weekly detailed report — the full picture, not the alert stream.
 *
 * Where {@code transport_manager_signals} answers "what needs me today", this answers
 * "what happened this week, across everything, and what should we do about it". It is
 * deliberately long: it is read once a week and is meant to be forwarded, so it carries
 * every metric with its reference, the OTA decomposition across all five dimensions, and
 * the vendor scorecard — each number with the point of comparison it is judged against.
 *
 * Stored like any other report, so it appears in the history and its facts are queryable
 * afterwards; the whole point of saving it is that a claim made in week 3 can be checked
 * in week 9.
 */
@Component
public class WeeklyDetailedReport implements ReportGenerator {

    private final MetricService metrics;
    private final MetricDegradationService degradation;
    private final OtaRootCauseService rootCause;
    private final SlaComplianceService compliance;
    private final RuleSetProperties rules;

    public WeeklyDetailedReport(MetricService metrics, MetricDegradationService degradation,
                                OtaRootCauseService rootCause, SlaComplianceService compliance,
                                RuleSetProperties rules) {
        this.metrics = metrics;
        this.degradation = degradation;
        this.rootCause = rootCause;
        this.compliance = compliance;
        this.rules = rules;
    }

    @Override public String key() { return "weekly_detailed_report"; }
    @Override public String personaCode() { return "FACILITIES_HEAD"; }

    @Override
    public GeneratedReport generate(Request r) {
        int window = rules.getSla().getOtaWindowMinutes();

        List<MetricWithContext> all = metrics.all(r.periodStart(), r.periodEnd(),
                r.businessUnit());
        List<Signal> signals = degradation.all(r.periodStart(), r.periodEnd(),
                r.businessUnit());
        Diagnosis ota = rootCause.diagnose(r.periodStart(), r.periodEnd(),
                r.businessUnit(), window);
        List<ComplianceRow> vendors = compliance.compliance(r.periodStart(), r.periodEnd(),
                r.businessUnit(), "vendor", window, 200);

        List<GeneratedReport.Fact> facts = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        // ---- 1. every metric, each against its reference
        body.append("<h2>Every metric, with its reference</h2><table>")
            .append("<tr><th>Metric</th><th>Value</th><th>Target</th><th>Prior</th>")
            .append("<th>Verdict</th></tr>");
        for (MetricWithContext m : all) {
            body.append("<tr><td>").append(esc(m.displayName()))
                .append("</td><td>").append(fmt(m.value(), m.unit()))
                .append("</td><td>").append(fmt(m.target(), m.unit()))
                .append("</td><td>").append(m.priorValue() == null ? "—"
                        : fmt(m.priorValue(), m.unit()))
                .append("</td><td>").append(m.status()).append("</td></tr>");
            facts.add(GeneratedReport.Fact.of(m.metric())
                    .value(m.value(), m.unit(), m.sampleSize())
                    .against(m.target(), "TARGET", "configured target")
                    .verdict(String.valueOf(m.status()))
                    .build());
        }
        body.append("</table>");

        // ---- 2. the OTA decomposition, all five dimensions
        body.append("<h2>On-time arrival — what moved it</h2>");
        if (ota.declined()) {
            body.append("<p>OTA fell ").append(fmt1(Math.abs(ota.otaChange())))
                .append(" points, ").append(fmt1(ota.otaPrev())).append("% to ")
                .append(fmt1(ota.otaNow())).append("%.</p>");
        } else {
            body.append("<p>OTA held at ").append(fmt1(ota.otaNow()))
                .append("% (").append(ota.otaChange() >= 0 ? "+" : "")
                .append(fmt1(ota.otaChange())).append(" points).</p>");
        }
        appendDrivers(body, facts, "Direction", ota.byDirection());
        appendDrivers(body, facts, "Shift band", ota.byShiftBand());
        appendDrivers(body, facts, "Cab type", ota.byProductType());
        appendDrivers(body, facts, "Office", ota.byOffice());
        appendDrivers(body, facts, "Vendor", ota.byVendor());

        // ---- 3. the vendor scorecard against the contract each signed
        long breaching = vendors.stream().filter(v -> !"MET".equals(v.status())).count();
        body.append("<h2>Vendors against the SLA they signed</h2><table>")
            .append("<tr><th>Vendor</th><th>OTA</th><th>Target</th><th>Trips</th>")
            .append("<th>Verdict</th></tr>");
        vendors.stream().filter(v -> !"MET".equals(v.status())).limit(20).forEach(v -> {
            body.append("<tr><td>").append(esc(v.vendor()))
                .append("</td><td>").append(fmt1(v.otaPct()))
                .append("%</td><td>").append(fmt1(v.target()))
                .append("%</td><td>").append(v.trips())
                .append("</td><td>").append(v.status()).append("</td></tr>");
            facts.add(GeneratedReport.Fact.of("ota")
                    .on("vendor", v.vendor())
                    .value(v.otaPct(), "percent", v.trips())
                    .against(v.target(), "SLA", v.slaName())
                    .verdict(v.status())
                    .build());
        });
        body.append("</table>");

        long degrading = signals.stream()
                .filter(s -> s.shape() == MetricDegradationService.Shape.SUDDEN
                          || s.shape() == MetricDegradationService.Shape.INCREMENTAL)
                .count();

        String headline = "Weekly review: %d of %d metrics degrading, %d vendor(s) below contract."
                .formatted(degrading, all.size(), breaching);

        String action = breaching > 0
                ? "Take the vendor table to the next contract review — %d vendor(s) are below the SLA they signed."
                        .formatted(breaching)
                : "No contract action needed. Watch the metrics flagged above.";

        // A weekly review is always worth reading, so it is always actionable: unlike the
        // alert stream, a quiet week is itself the finding and must still be delivered.
        return new GeneratedReport(key(), personaCode(), r.businessUnit(),
                r.periodStart(), r.periodEnd(), r.compareStart(), r.compareEnd(),
                headline, body.toString(), action,
                Math.min(100, degrading * 20 + breaching * 5), true, "RULES", facts);
    }

    private void appendDrivers(StringBuilder body, List<GeneratedReport.Fact> facts,
                               String label, List<Driver> drivers) {
        if (drivers == null || drivers.isEmpty()) return;
        body.append("<h3>By ").append(esc(label)).append("</h3><table>")
            .append("<tr><th>Slice</th><th>Now</th><th>Before</th><th>Points explained</th></tr>");
        drivers.stream().limit(5).forEach(d -> {
            body.append("<tr><td>").append(esc(d.value()))
                .append("</td><td>").append(fmt1(d.otaNow()))
                .append("%</td><td>").append(fmt1(d.otaPrev()))
                .append("%</td><td>").append(fmt1(d.contributionPts()))
                .append("</td></tr>");
            facts.add(GeneratedReport.Fact.of("ota")
                    .on(d.dimension(), d.value())
                    .value(d.otaNow(), "percent", d.tripsNow())
                    .against(d.otaPrev(), "PRIOR_PERIOD", "the window before")
                    .verdict(d.contributionPts() < 0 ? "BREACH" : "MET")
                    .contribution(d.contributionPts())
                    .evidence(d.evidenceSql())
                    .build());
        });
        body.append("</table>");
    }

    private static String fmt(double v, String unit) {
        return switch (unit) {
            case "percent" -> fmt1(v) + "%";
            case "currency" -> "₹" + Math.round(v);
            case "rating" -> "%.2f".formatted(v);
            default -> fmt1(v);
        };
    }

    private static String fmt1(double v) { return "%.1f".formatted(v); }

    /** Vendor and office names come from the data, so they are escaped before templating. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
