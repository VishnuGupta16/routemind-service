package com.routemind.api;

import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.narrative.NarrativeService;
import com.routemind.onboarding.OnboardingService;
import com.routemind.onboarding.SourceProfile.ColumnProfile;
import com.routemind.onboarding.SourceProfile.Proposal;
import com.routemind.persona.Persona;
import com.routemind.persona.PersonaRouter;
import com.routemind.persona.PersonaRouter.PersonaBundle;
import com.routemind.persona.ShiftReadinessService;
import com.routemind.report.ReportComposer;
import com.routemind.rules.RuleSetProperties;
import com.routemind.schedule.ProactiveScanner;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping()
@CrossOrigin
public class InsightController {

    private final MetricService metrics;
    private final PersonaRouter router;
    private final ShiftReadinessService shifts;
    private final ReportComposer reports;
    private final ProactiveScanner scanner;
    private final NarrativeService narrative;
    private final OnboardingService onboarding;
    private final RuleSetProperties rules;

    public InsightController(MetricService metrics, PersonaRouter router,
                             ShiftReadinessService shifts, ReportComposer reports,
                             ProactiveScanner scanner, NarrativeService narrative,
                             OnboardingService onboarding, RuleSetProperties rules) {
        this.metrics = metrics;
        this.router = router;
        this.shifts = shifts;
        this.reports = reports;
        this.scanner = scanner;
        this.narrative = narrative;
        this.onboarding = onboarding;
        this.rules = rules;
    }

    // ---------------------------------------------------------------- metrics
    @GetMapping("/metrics")
    public List<MetricWithContext> all(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String businessUnit) {
        return metrics.all(from, to, businessUnit);
    }

    // ------------------------------------------------------ personas (all 3)
    @GetMapping("/personas")
    public List<Map<String, Object>> personas() {
        return java.util.Arrays.stream(Persona.values())
                .map(p -> Map.<String, Object>of(
                        "id", p.name(), "displayName", p.displayName(), "need", p.need(),
                        "cadence", p.cadence().name(), "channel", p.channel().name(),
                        "metrics", router.metricsFor(p)))
                .toList();
    }

    @GetMapping("/insights/{persona}")
    public PersonaBundle insights(@PathVariable String persona,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) String businessUnit,
                                  @RequestParam(defaultValue = "5") int limit) {
        return router.bundle(Persona.of(persona), from, to, businessUnit, limit);
    }

    @GetMapping("/insights")
    public List<PersonaBundle> allInsights(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) String businessUnit,
                                           @RequestParam(defaultValue = "3") int limit) {
        return router.allPersonas(from, to, businessUnit, limit);
    }

    // ------------------------------------------ line-manager lens: shift view
    @GetMapping("/shifts")
    public List<ShiftReadinessService.ShiftRow> shiftReadiness(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String businessUnit) {
        return shifts.forDay(day, businessUnit, rules.getSla().getOtaWindowMinutes());
    }

    /**
     * Per-employee detail — "which six people are not here yet", which is what a line
     * manager actually acts on.
     */
    @GetMapping("/shifts/employees")
    public List<ShiftReadinessService.EmployeeStatus> shiftEmployees(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String shift,
            @RequestParam(required = false) String office,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "50") int limit) {
        return shifts.employeesForShift(day, shift, office, businessUnit, limit);
    }

    // ---------------------------------------------------------------- report
    @GetMapping(value = "/report/{persona}", produces = MediaType.TEXT_HTML_VALUE)
    public String report(@PathVariable String persona,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) String businessUnit) {
        return reports.html(router.bundle(Persona.of(persona), from, to, businessUnit, 6));
    }

    // ------------------------------------------------- proactive (self-trigger)
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        scanner.scanFor(asOf == null ? LocalDate.now() : asOf);
        return scanner.status();
    }

    @GetMapping("/scan/status")
    public Map<String, Object> scanStatus() { return scanner.status(); }

    // ------------------------------------------------------------- onboarding
    @PostMapping("/onboarding/propose")
    public Proposal propose(@RequestBody Map<String, Object> body) {
        String sourceId = String.valueOf(body.getOrDefault("sourceId", "new-source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) body.get("columns");
        List<ColumnProfile> profiles = cols.stream().map(c -> new ColumnProfile(
                String.valueOf(c.get("name")),
                String.valueOf(c.getOrDefault("type", "TEXT")),
                100.0,
                ((Number) c.getOrDefault("distinct", 0)).longValue(),
                castSamples(c.get("samples")))).toList();
        return onboarding.propose(sourceId, profiles);
    }

    @GetMapping("/onboarding/discover/{table}")
    public Proposal discover(@PathVariable String table) {
        return onboarding.propose(table, onboarding.discover(table));
    }

    @SuppressWarnings("unchecked")
    private static List<String> castSamples(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    // ------------------------------------------------------------------ meta
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "metrics", metrics.metricIds(),
                "targets", rules.getTargets(),
                "otaWindowMinutes", rules.getSla().getOtaWindowMinutes(),
                "narrativeGenerator", narrative.activeGenerator(),
                "narrativeCacheSize", narrative.cacheSize(),
                "trigger", scanner.status());
    }
}
