package com.routemind.api;

import com.routemind.diagnose.MetricDegradationService;
import com.routemind.diagnose.MetricDegradationService.Signal;
import com.routemind.diagnose.OtaDiagnosisService;
import com.routemind.diagnose.OtaDiagnosisService.DualAnswer;
import com.routemind.diagnose.OtaRootCauseService;
import com.routemind.diagnose.OtaRootCauseService.Diagnosis;
import com.routemind.rules.RuleSetProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * "Why is OTA down?" — the demo's headline question.
 *
 * Two endpoints on purpose:
 *   /api/diagnose/ota        the deterministic decomposition alone (the defensible answer)
 *   /api/diagnose/ota/dual   that PLUS the AI narrative over the same numbers, side by side
 *
 * The dual endpoint is what the demo shows: the rule-based track proves the AI track is not
 * inventing anything, and the AI track makes the rule-based track readable.
 */
@RestController
@RequestMapping("/diagnose")
@CrossOrigin
public class DiagnoseController {

    private final OtaRootCauseService rootCause;
    private final OtaDiagnosisService diagnosis;
    private final MetricDegradationService degradation;
    private final RuleSetProperties rules;

    public DiagnoseController(OtaRootCauseService rootCause, OtaDiagnosisService diagnosis,
                              MetricDegradationService degradation, RuleSetProperties rules) {
        this.rootCause = rootCause;
        this.diagnosis = diagnosis;
        this.degradation = degradation;
        this.rules = rules;
    }

    /** The arithmetic answer: totals, drivers by every dimension, cause-mix shift. */
    @GetMapping("/ota")
    public Diagnosis ota(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return rootCause.diagnose(from, to, businessUnit,
                rules.getSla().getOtaWindowMinutes());
    }

    /** The same, plus the rule-based and AI narratives to compare. */
    @GetMapping("/ota/dual")
    public DualAnswer otaDual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return diagnosis.diagnose(from, to, businessUnit,
                rules.getSla().getOtaWindowMinutes());
    }

    /**
     * The transport manager's board: every metric that is degrading, classified SUDDEN vs
     * INCREMENTAL, ranked by urgency, each with the reason attached.
     */
    @GetMapping("/degrading")
    public List<Signal> degrading(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return degradation.degrading(from, to, businessUnit);
    }

    /** The fuller board — every metric with its shape, degrading or not. */
    @GetMapping("/signals")
    public List<Signal> signals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return degradation.all(from, to, businessUnit);
    }
}
