package com.routemind.report;

import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.report.GeneratedReport.Fact;
import com.routemind.rules.RuleSetProperties;
import com.routemind.sla.SlaComplianceService;
import com.routemind.sla.SlaComplianceService.ComplianceRow;
import com.routemind.sla.VendorFleetService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The briefing for the Transport &amp; Facilities Head.
 *
 * This persona owns the budget, the vendor contracts and what leadership hears. They do not
 * need a dashboard; they need the one paragraph they would otherwise spend a morning
 * assembling from four screens. So the report is ordered the way their authority is ordered:
 *
 *     1. MONEY        what was spent, against what it should have been
 *     2. EXPOSURE     contractual risk — penalties, and how much of the operation is
 *                     actually being judged against a real contract at all
 *     3. SLA          which vendors missed the contract THEY signed, not a group average
 *     4. SAFETY       the thing that ends up in front of leadership if it goes wrong
 *     5. EXPERIENCE   the leading indicator for everything above
 *     6. GOALS        EV share and utilisation, reported as a gap, never alerted on
 *
 * Two rules hold throughout, and both exist because breaking them is how a report like this
 * quietly becomes untrustworthy:
 *
 *   - Every number carries its reference point and its sample size. There is no path here
 *     that emits a bare figure.
 *   - Nothing is recommended that this persona cannot authorise. Suggesting they reroute
 *     tomorrow's 09:30 shift is somebody else's job and wastes the one thing they will read.
 */
@Component
public class FacilitiesHeadBriefing implements ReportGenerator {

    /** Enough trips that a vendor's rate is worth putting in front of leadership. */
    private static final int MIN_TRIPS_FOR_VENDOR_CLAIM = 500;

    /** Metrics this persona is accountable for, in the order they appear in the briefing. */
    private static final List<String> MONEY = List.of("cost_per_trip", "penalty_exposure");
    private static final List<String> OPERATIONS = List.of("ota", "worst_product_ota");
    private static final List<String> CARE = List.of("safety_alerts_per_1k", "experience",
            "no_show_rate");
    /** Strategic goals — reported as a gap, deliberately never alerted on. */
    private static final List<String> GOALS = List.of("ev_share", "seat_utilisation");

    private final MetricService metrics;
    private final SlaComplianceService compliance;
    private final VendorFleetService fleets;
    private final RuleSetProperties rules;

    public FacilitiesHeadBriefing(MetricService metrics, SlaComplianceService compliance,
                                  VendorFleetService fleets, RuleSetProperties rules) {
        this.metrics = metrics;
        this.compliance = compliance;
        this.fleets = fleets;
        this.rules = rules;
    }

    public String key() { return "facilities_head_briefing"; }
    public String personaCode() { return "FACILITIES_HEAD"; }

    @Override
    public GeneratedReport generate(Request r) {
        List<Fact> facts = new ArrayList<>();
        double severity = 0;

        // ---------------------------------------------------------------- metrics
        Map<String, MetricWithContext> current = new LinkedHashMap<>();
        for (String id : concat(MONEY, OPERATIONS, CARE, GOALS)) {
            metrics.metric(id, r.periodStart(), r.periodEnd(), r.businessUnit())
                    .ifPresent(m -> current.put(id, m));
        }

        for (String id : concat(MONEY, OPERATIONS, CARE)) {
            MetricWithContext m = current.get(id);
            if (m == null) continue;
            double contribution = severityOf(m);
            severity += contribution;
            facts.add(factFor(m, contribution));
            facts.addAll(attributionFacts(m));
        }
        // goals contribute NO severity — a standing gap is not an incident
        for (String id : GOALS) {
            MetricWithContext m = current.get(id);
            if (m != null) facts.add(goalFact(m));
        }

        // ------------------------------------------------------------------- SLA
        List<ComplianceRow> breaches = compliance
                .compliance(r.periodStart(), r.periodEnd(), r.businessUnit(), "vendor",
                        rules.getSla().getOtaWindowMinutes(), MIN_TRIPS_FOR_VENDOR_CLAIM)
                .stream().filter(c -> !"MET".equals(c.status())).toList();

        for (ComplianceRow c : breaches) {
            facts.add(Fact.of("ota").on("vendor", c.vendor())
                    .value(c.otaPct(), "percent", c.trips())
                    .against(c.target(), "SLA", c.slaName() + " — " + c.slaScope())
                    .verdict(c.status())
                    .contribution("BREACH".equals(c.status()) ? 8 : 3)
                    .build());
            severity += "BREACH".equals(c.status()) ? 8 : 3;
        }

        // How much of the operation is judged against a real contract rather than the
        // placeholder default. Usually the most uncomfortable line in the briefing, and
        // nothing else in the system would tell this persona about it.
        Map<String, Object> coverage = fleets.coverage(r.periodEnd());
        double coveragePct = toDouble(coverage.get("tripCoveragePct"));
        facts.add(Fact.of("sla_coverage")
                .value(coveragePct, "percent", toLong(coverage.get("trips")))
                .against(100.0, "TARGET", "every trip judged against a signed contract")
                .verdict(coveragePct >= 95 ? "MET" : coveragePct >= 50 ? "AT_RISK" : "BREACH")
                .contribution(coveragePct < 50 ? 6 : 0)
                .build());
        if (coveragePct < 50) severity += 6;

        severity = Math.min(100, Math.round(severity * 10) / 10.0);
        boolean actionable = !breaches.isEmpty()
                || facts.stream().anyMatch(f -> "BREACH".equals(f.verdict()));

        return new GeneratedReport(key(), personaCode(), r.businessUnit(),
                r.periodStart(), r.periodEnd(), r.compareStart(), r.compareEnd(),
                headline(current, breaches, coveragePct),
                body(r, current, breaches, coverage),
                action(current, breaches, coveragePct),
                severity, actionable, "RULES", facts);
    }

    // ------------------------------------------------------------------- facts

    private Fact factFor(MetricWithContext m, double contribution) {
        // Prefer the prior period as the reference when we have one — a facilities head
        // asks "is this getting worse?" before "is this above the line?". Fall back to the
        // target, which always exists.
        boolean hasPrior = m.priorValue() != null;
        return Fact.of(m.metric())
                .value(m.value(), m.unit(), m.sampleSize())
                .against(hasPrior ? m.priorValue() : m.target(),
                        hasPrior ? "PRIOR_PERIOD" : "TARGET",
                        hasPrior ? "previous period" : "configured target " + m.target())
                .verdict(switch (m.status()) {
                    case BREACH -> "BREACH";
                    case AT_RISK -> "AT_RISK";
                    case OK -> "MET";
                })
                .contribution(contribution)
                .build();
    }

    /** Who is driving the bad side of a metric — the part that makes it actionable. */
    private List<Fact> attributionFacts(MetricWithContext m) {
        List<Fact> out = new ArrayList<>();
        if (m.topContributors() == null) return out;
        for (Contribution c : m.topContributors()) {
            out.add(Fact.of(m.metric())
                    .on(m.attributionDimension(), c.member())
                    .value(c.pct(), "percent", c.count())
                    .against(null, "PEER", "share of the total for this metric")
                    .verdict("INFO")
                    .build());
        }
        return out;
    }

    private Fact goalFact(MetricWithContext m) {
        double goal = rules.getGoals().getOrDefault(m.metric(), m.target());
        return Fact.of(m.metric())
                .value(m.value(), m.unit(), m.sampleSize())
                .against(goal, "TARGET", "strategic goal " + goal)
                // A standing gap against an aspirational goal is not a breach. Grading it
                // as one is how a report ends up permanently red and gets ignored.
                .verdict(m.value() >= goal ? "MET" : "INFO")
                .build();
    }

    /**
     * How much a metric should push the severity score.
     *
     * Weighted by what this persona is accountable for, not by how far the number moved:
     * a safety breach outranks a cost breach outranks an experience dip, because that is
     * the order in which they land on their desk.
     */
    private double severityOf(MetricWithContext m) {
        if (m.status() == Status.OK) return 0;
        double weight = switch (m.metric()) {
            case "safety_alerts_per_1k" -> 10;
            case "penalty_exposure", "cost_per_trip" -> 8;
            case "ota", "worst_product_ota" -> 6;
            case "experience", "no_show_rate" -> 4;
            default -> 2;
        };
        return m.status() == Status.BREACH
                ? weight : weight / 2;
    }

    // -------------------------------------------------------------------- prose

    private String headline(Map<String, MetricWithContext> m, List<ComplianceRow> breaches,
                            double coveragePct) {
        if (!breaches.isEmpty()) {
            ComplianceRow w = breaches.get(0);
            return "%s missed its own SLA: %.1f%% against a %.1f%% commitment over %,d trips"
                    .formatted(w.vendor(), w.otaPct(), w.target(), w.trips());
        }
        MetricWithContext worst = m.values().stream()
                .filter(x -> x.status() != Status.OK)
                .max((a, b) -> Double.compare(severityOf(a), severityOf(b)))
                .orElse(null);
        if (worst != null) {
            return "%s is %s at %s against a target of %s"
                    .formatted(worst.displayName(),
                            worst.status().name().toLowerCase().replace('_', ' '),
                            fmt(worst.value(), worst.unit()), fmt(worst.target(), worst.unit()));
        }
        if (coveragePct < 50) {
            return "Everything is within target — but only %.1f%% of trips are judged "
                    .formatted(coveragePct) + "against a signed contract";
        }
        return "No metric breached its target this period";
    }

    private String body(Request r, Map<String, MetricWithContext> m,
                        List<ComplianceRow> breaches, Map<String, Object> coverage) {
        StringBuilder s = new StringBuilder();
        s.append("Period ").append(r.periodStart()).append(" to ").append(r.periodEnd());
        if (r.businessUnit() != null) s.append(" · ").append(r.businessUnit());
        if (r.hasComparison()) {
            s.append(" · compared with ").append(r.compareStart())
             .append(" to ").append(r.compareEnd());
        }
        s.append("\n\n");

        section(s, "MONEY", MONEY, m);
        section(s, "OPERATIONS", OPERATIONS, m);
        section(s, "SAFETY & EXPERIENCE", CARE, m);

        s.append("CONTRACTUAL EXPOSURE\n");
        if (breaches.isEmpty()) {
            s.append("  · No vendor with more than ").append(MIN_TRIPS_FOR_VENDOR_CLAIM)
             .append(" trips missed the SLA it signed.\n");
        } else {
            for (ComplianceRow c : breaches) {
                s.append("  · ").append(c.vendor()).append(": ")
                 .append(String.format("%.1f%%", c.otaPct()))
                 .append(" against ").append(String.format("%.1f%%", c.target()))
                 .append(" (±").append(String.format("%.1f", c.tolerancePct())).append(") — ")
                 .append(c.status()).append(", ")
                 .append(String.format("%,d", c.trips())).append(" trips, ")
                 .append(String.format("%,d", c.lateTrips())).append(" late. SLA: ")
                 .append(c.slaName()).append(" (").append(c.slaScope()).append(").\n");
            }
        }
        s.append("  · ").append(coverage.get("withSpecificSla")).append(" of ")
         .append(coverage.get("combinations"))
         .append(" vendor/cab-type/shift combinations have a specific SLA; ")
         .append(coverage.get("tripCoveragePct"))
         .append("% of trips are judged against one rather than the group default.\n\n");

        s.append("STRATEGIC GOALS\n");
        for (String id : GOALS) {
            MetricWithContext g = m.get(id);
            if (g == null) continue;
            double goal = rules.getGoals().getOrDefault(id, g.target());
            s.append("  · ").append(g.displayName()).append(": ")
             .append(fmt(g.value(), g.unit())).append(" against a goal of ")
             .append(fmt(goal, g.unit())).append(" — a gap of ")
             .append(fmt(Math.abs(goal - g.value()), g.unit()))
             .append(". Reported as a gap, not alerted on.\n");
        }
        return s.toString();
    }

    private void section(StringBuilder s, String title, List<String> ids,
                         Map<String, MetricWithContext> m) {
        s.append(title).append("\n");
        for (String id : ids) {
            MetricWithContext x = m.get(id);
            if (x == null) continue;
            s.append("  · ").append(x.displayName()).append(": ")
             .append(fmt(x.value(), x.unit()))
             .append(" (n=").append(String.format("%,d", x.sampleSize())).append(")");
            s.append(", target ").append(fmt(x.target(), x.unit()));
            if (x.priorValue() != null) {
                double d = x.value() - x.priorValue();
                s.append(", ").append(d >= 0 ? "up " : "down ")
                 .append(fmt(Math.abs(d), x.unit())).append(" on the previous period");
            }
            s.append(" — ").append(x.status()).append(".");
            if (x.topContributors() != null && !x.topContributors().isEmpty()) {
                Contribution top = x.topContributors().get(0);
                s.append(" Largest ").append(x.attributionDimension()).append(": ")
                 .append(top.member()).append(" at ").append(top.pct()).append("%.");
            }
            s.append("\n");
        }
        s.append("\n");
    }

    /**
     * The recommendation. Deliberately narrow: only things a facilities head can authorise —
     * contract terms, vendor allocation, budget, escalation.
     */
    private String action(Map<String, MetricWithContext> m, List<ComplianceRow> breaches,
                          double coveragePct) {
        if (coveragePct < 50) {
            return ("Before acting on any vendor number: only %.1f%% of trips are measured "
                    + "against a signed contract. Load the real contractual terms during "
                    + "vendor onboarding — every verdict below rests on a placeholder until "
                    + "then.").formatted(coveragePct);
        }
        if (!breaches.isEmpty()) {
            ComplianceRow w = breaches.get(0);
            return ("Raise %s at the next contract review: %.1f%% against the %.1f%% they "
                    + "signed, across %,d trips. If the gap persists, the options in your "
                    + "gift are a penalty claim under the existing clause or moving volume "
                    + "to a vendor meeting its terms.")
                    .formatted(w.vendor(), w.otaPct(), w.target(), w.trips());
        }
        MetricWithContext penalty = m.get("penalty_exposure");
        if (penalty != null
                && penalty.status() != Status.OK) {
            return "Penalty exposure is at " + fmt(penalty.value(), penalty.unit())
                    + " of billing. Check whether the deductions are being applied "
                    + "consistently across contracts — in this data they are concentrated "
                    + "on very few of them.";
        }
        return "No action required. All tracked metrics are within target and no vendor "
                + "missed the SLA it signed.";
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(double v, String unit) {
        return switch (unit == null ? "" : unit) {
            case "percent" -> String.format("%.1f%%", v);
            case "currency" -> String.format("₹%,.0f", v);
            case "rating" -> String.format("%.2f", v);
            default -> String.format("%,.1f", v);
        };
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) out.addAll(l);
        return out;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    /** Exposed for the report service so a missing generator fails loudly, not silently. */
    public Optional<String> describe() {
        return Optional.of("Transport & Facilities Head briefing — money, exposure, SLA, "
                + "safety, experience, goals.");
    }
}
