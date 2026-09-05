package com.routemind.api;

import com.routemind.rules.RuleSetProperties;
import com.routemind.sla.SlaComplianceService;
import com.routemind.sla.SlaComplianceService.ComplianceRow;
import com.routemind.sla.SlaPolicy;
import com.routemind.sla.SlaPolicyService;
import com.routemind.sla.VendorFleet;
import com.routemind.sla.VendorFleetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Vendor onboarding: configure the SLA this vendor actually signed, then measure them
 * against it. Scope fields left blank mean "any", so one row can cover the whole group.
 */
@RestController
@RequestMapping("/api/sla")
@CrossOrigin
public class SlaController {

    private final SlaPolicyService policies;
    private final SlaComplianceService compliance;
    private final VendorFleetService fleets;
    private final RuleSetProperties rules;

    public SlaController(SlaPolicyService policies, SlaComplianceService compliance,
                         VendorFleetService fleets, RuleSetProperties rules) {
        this.policies = policies;
        this.compliance = compliance;
        this.fleets = fleets;
        this.rules = rules;
    }

    // ------------------------------------------------------------- policies
    @GetMapping("/policies")
    public List<Map<String, Object>> list() {
        return policies.all().stream().map(p -> {
            // LinkedHashMap rather than Map.of: this has outgrown Map.of's 10-pair limit,
            // and the UI renders the columns in insertion order.
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("scope", p.scopeLabel());
            m.put("terms", p.termsLabel());
            m.put("specificity", p.specificity());
            m.put("businessUnit", p.businessUnit());
            m.put("vendor", p.vendor());
            m.put("productType", p.productType());
            m.put("shiftType", p.shiftType());
            m.put("otaWindowMinutes", p.otaWindowMinutes());
            m.put("otaTarget", p.otaTarget());
            m.put("tolerancePct", p.tolerance());
            m.put("effectiveFrom", p.effectiveFrom());
            m.put("effectiveTo", p.effectiveTo());
            m.put("active", p.active());
            return m;
        }).toList();
    }

    /** Called during vendor onboarding. */
    @PostMapping("/policies")
    public SlaPolicy create(@RequestBody SlaPolicy body) { return policies.create(body); }

    @PutMapping("/policies/{id}")
    public ResponseEntity<SlaPolicy> update(@PathVariable long id, @RequestBody SlaPolicy body) {
        return policies.update(id, body).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable long id) {
        return policies.deactivate(id)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Which SLA would apply to a given combination — use this to check a rule lands
     * where you expect before trusting it.
     */
    @GetMapping("/resolve")
    public ResponseEntity<SlaPolicy> resolve(
            @RequestParam(required = false) String businessUnit,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String shiftType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return policies.resolve(businessUnit, vendor, productType, shiftType,
                        on == null ? LocalDate.now() : on)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ----------------------------------------------------------------- fleet
    /**
     * Every vendor × cab type × shift time actually operating, each with the SLA that
     * applies to it and the resulting verdict. This is the onboarding surface: you
     * configure a target against a combination the data says exists, with its current
     * on-time rate visible while you choose.
     */
    @GetMapping("/fleet")
    public List<VendorFleet> fleet(
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return fleets.all(vendor, businessUnit, on);
    }

    @GetMapping("/fleet/vendors")
    public List<String> fleetVendors() { return fleets.vendors(); }

    /** What this vendor actually runs — drives the dropdowns on the onboarding form. */
    @GetMapping("/fleet/scope")
    public Map<String, Object> operatingScope(@RequestParam String vendor) {
        return fleets.operatingScope(vendor);
    }

    /**
     * How much of the operation is judged against a real contract rather than the
     * placeholder default. Usually the most uncomfortable number on the screen.
     */
    @GetMapping("/fleet/coverage")
    public Map<String, Object> coverage(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return fleets.coverage(on);
    }

    /** Recompute the fleet from the trip data. Run after each monthly load. */
    @PostMapping("/fleet/refresh")
    public Map<String, Object> refreshFleet() {
        return Map.of("combinations", fleets.refresh());
    }

    // ----------------------------------------------------------- compliance
    /**
     * The vendor scorecard — each vendor judged against the SLA it signed.
     * groupBy: vendor | vendor_product | vendor_shift
     */
    @GetMapping("/compliance")
    public List<ComplianceRow> compliance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit,
            @RequestParam(defaultValue = "vendor") String groupBy,
            @RequestParam(defaultValue = "500") int minTrips) {
        return compliance.compliance(from, to, businessUnit, groupBy,
                rules.getSla().getOtaWindowMinutes(), minTrips);
    }
}
