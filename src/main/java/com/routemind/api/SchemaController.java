package com.routemind.api;

import com.routemind.schema.SchemaChange;
import com.routemind.schema.SchemaChange.Profile;
import com.routemind.schema.SchemaRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Schema evolution, driven from the UI: the pipeline posts what it found, the operator
 * adopts or ignores each change, and adoption applies backward-compatible DDL.
 */
@RestController
@RequestMapping("/schema")
@CrossOrigin
public class SchemaController {

    private final SchemaRegistry registry;

    public SchemaController(SchemaRegistry registry) { this.registry = registry; }

    /** Everything detected, decided or not. */
    @GetMapping("/changes")
    public List<SchemaChange> changes(@RequestParam(defaultValue = "false") boolean pendingOnly) {
        return pendingOnly ? registry.pending() : registry.all();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() { return registry.summary(); }

    /**
     * Ingest a validation report produced by `etl/validate.py --json`.
     * Turns UNEXPECTED_COLUMN / NEW_ENUM_VALUE findings into pending decisions.
     */
    @PostMapping("/report")
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestReport(@RequestBody Map<String, Object> report) {
        List<Map<String, Object>> issues =
                (List<Map<String, Object>>) report.getOrDefault("issues", List.of());
        int created = 0;

        for (Map<String, Object> i : issues) {
            String code = String.valueOf(i.get("code"));
            String source = String.valueOf(i.get("source"));
            String column = String.valueOf(i.get("column"));
            String message = String.valueOf(i.getOrDefault("message", ""));
            if ("null".equals(column)) continue;

            if ("UNEXPECTED_COLUMN".equals(code)) {
                Map<String, Object> pr = (Map<String, Object>) i.get("profile");
                Profile profile = pr == null
                        ? new Profile("TEXT", 100.0, 0, List.of())
                        : new Profile(String.valueOf(pr.getOrDefault("type", "TEXT")),
                        toD(pr.get("nonNullPct"), 100.0),
                        (long) toD(pr.get("distinct"), 0),
                        (List<String>) pr.getOrDefault("samples", List.of()));
                LocalDate from = i.get("availableFrom") == null ? null
                        : LocalDate.parse(String.valueOf(i.get("availableFrom")));
                registry.recordNewColumn(source, column, message, from, profile);
                created++;
            } else if ("NEW_ENUM_VALUE".equals(code)) {
                List<String> vals = (List<String>) i.getOrDefault("values", List.of());
                registry.recordNewEnumValue(source, column, message, vals);
                created++;
            }
        }
        return Map.of("ingested", created, "pending", registry.pending().size());
    }

    /**
     * Adopt: add the column NULLABLE with no backfill, so historical rows read NULL
     * ("not collected then") and no existing metric changes value.
     */
    @PostMapping("/changes/{id}/adopt")
    public ResponseEntity<SchemaChange> adopt(@PathVariable String id,
                                              @RequestParam(defaultValue = "operator") String by,
                                              @RequestParam(defaultValue = "true") boolean applyNow) {
        return registry.adopt(id, by, applyNow)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/changes/{id}/ignore")
    public ResponseEntity<SchemaChange> ignore(@PathVariable String id,
                                               @RequestParam(defaultValue = "operator") String by,
                                               @RequestParam(required = false) String reason) {
        return registry.ignore(id, by, reason)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/changes/{id}/reject")
    public ResponseEntity<SchemaChange> reject(@PathVariable String id,
                                               @RequestParam(defaultValue = "operator") String by,
                                               @RequestParam(required = false) String reason) {
        return registry.reject(id, by, reason)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /**
     * The decisions overlay — `etl/validate.py` reads this so an adopted or ignored
     * column stops being reported as a surprise on the next monthly drop.
     */
    @GetMapping("/decisions")
    public Map<String, Object> decisions() { return registry.decisions(); }

    private static double toD(Object o, double dflt) {
        return o instanceof Number n ? n.doubleValue() : dflt;
    }
}
