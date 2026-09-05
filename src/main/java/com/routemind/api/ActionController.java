package com.routemind.api;

import com.routemind.action.ActionService;
import com.routemind.action.ActionService.ProposedAction;
import com.routemind.action.ActionService.State;
import com.routemind.persona.Persona;
import com.routemind.persona.PersonaRouter;
import com.routemind.rules.Finding;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The "act" loop: propose → human approves/rejects → executed, with an audit trail.
 */
@RestController
@RequestMapping("/api/actions")
@CrossOrigin
public class ActionController {

    private final ActionService actions;
    private final PersonaRouter router;

    public ActionController(ActionService actions, PersonaRouter router) {
        this.actions = actions;
        this.router = router;
    }

    /** Everything on the desk, or filtered by state. */
    @GetMapping
    public List<ProposedAction> list(@RequestParam(required = false) State state) {
        return actions.list(state);
    }

    /** Materialise actions from the current findings for a persona. */
    @PostMapping("/propose")
    public List<ProposedAction> propose(
            @RequestParam(defaultValue = "FACILITIES_HEAD") String persona,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {

        List<Finding> findings = router.bundle(Persona.of(persona), from, to, businessUnit, 10)
                .findings();
        return findings.stream()
                .map(actions::proposeFrom)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ProposedAction> approve(@PathVariable long id,
                                                  @RequestParam(defaultValue = "operator") String by) {
        return actions.approve(id, by).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ProposedAction> reject(@PathVariable long id,
                                                 @RequestParam(defaultValue = "operator") String by,
                                                 @RequestParam(required = false) String reason) {
        return actions.reject(id, by, reason).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProposedAction> get(@PathVariable long id) {
        return actions.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() { return actions.stats(); }
}
