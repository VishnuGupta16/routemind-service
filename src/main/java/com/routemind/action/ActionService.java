package com.routemind.action;

import com.routemind.narrative.TemplateNarrativeGenerator;
import com.routemind.rules.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closes the "acts" half of sense → reason → ACT.
 *
 * The system proposes a concrete action for each finding, a human approves or rejects it,
 * and the decision is recorded with its outcome. Nothing touching the real world happens
 * without approval — but the loop is complete and auditable, which is the difference
 * between a dashboard and an agent.
 *
 * State lives in memory here; in production this is one table. The execution step is a
 * seam: {@link #execute} is where a dispatch/vendor-system integration would go.
 */
@Service
public class ActionService {

    public enum State { PROPOSED, APPROVED, REJECTED, EXECUTED }

    public record ProposedAction(long id,
                                 String findingKey,
                                 String metricId,
                                 String displayName,
                                 String businessUnit,
                                 String target,          // the vendor/route being acted on
                                 String title,
                                 String rationale,
                                 double expectedImpactPct,
                                 State state,
                                 Instant proposedAt,
                                 Instant decidedAt,
                                 String decidedBy,
                                 String outcome) {

        ProposedAction transition(State s, String by, String note) {
            return new ProposedAction(id, findingKey, metricId, displayName, businessUnit,
                    target, title, rationale, expectedImpactPct, s, proposedAt,
                    Instant.now(), by, note);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    private final Map<Long, ProposedAction> actions = new ConcurrentHashMap<>();
    private final Map<String, Long> byFinding = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    /** Idempotent: one open action per finding, so a repeated scan doesn't spam. */
    public Optional<ProposedAction> proposeFrom(Finding f) {
        String key = f.dedupeKey();
        Long existing = byFinding.get(key);
        if (existing != null) {
            ProposedAction a = actions.get(existing);
            if (a != null && (a.state() == State.PROPOSED || a.state() == State.APPROVED)) {
                return Optional.of(a);           // already on someone's desk
            }
        }

        String title = TemplateNarrativeGenerator.action(f);
        if (title == null) return Optional.empty();      // no meaningful action for this metric

        String target = f.attribution() == null || f.attribution().isEmpty()
                ? null : f.attribution().get(0).member();
        double impact = f.attribution() == null || f.attribution().isEmpty()
                ? 0 : f.attribution().get(0).pct();

        long id = seq.incrementAndGet();
        ProposedAction a = new ProposedAction(id, key, f.metricId(), f.displayName(),
                f.businessUnit(), target, title, f.narrative(), impact,
                State.PROPOSED, Instant.now(), null, null, null);

        actions.put(id, a);
        byFinding.put(key, id);
        log.info("Proposed action {} for {} (target={})", id, f.metricId(), target);
        return Optional.of(a);
    }

    public Optional<ProposedAction> approve(long id, String by) {
        ProposedAction a = actions.get(id);
        if (a == null || a.state() != State.PROPOSED) return Optional.ofNullable(a);
        ProposedAction approved = a.transition(State.APPROVED, by, "approved, awaiting execution");
        actions.put(id, approved);
        return Optional.of(execute(approved));
    }

    public Optional<ProposedAction> reject(long id, String by, String reason) {
        ProposedAction a = actions.get(id);
        if (a == null || a.state() != State.PROPOSED) return Optional.ofNullable(a);
        ProposedAction rejected = a.transition(State.REJECTED, by,
                reason == null || reason.isBlank() ? "rejected" : reason);
        actions.put(id, rejected);
        return Optional.of(rejected);
    }

    /**
     * SEAM: the only place that would touch an external system.
     * Today it records the outcome; a real deployment raises the vendor ticket,
     * pushes the roster change, or calls the dispatch API here.
     */
    private ProposedAction execute(ProposedAction a) {
        String outcome = "Recorded. Integration point: raise with " +
                (a.target() == null ? "the vendor" : a.target()) +
                " via the vendor-management system.";
        ProposedAction done = a.transition(State.EXECUTED, a.decidedBy(), outcome);
        actions.put(a.id(), done);
        log.info("Executed action {} ({})", a.id(), a.metricId());
        return done;
    }

    public List<ProposedAction> list(State state) {
        return actions.values().stream()
                .filter(a -> state == null || a.state() == state)
                .sorted(Comparator.comparingDouble(ProposedAction::expectedImpactPct).reversed())
                .toList();
    }

    public Optional<ProposedAction> get(long id) { return Optional.ofNullable(actions.get(id)); }

    public Map<String, Object> stats() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (State s : State.values()) {
            counts.put(s.name(), actions.values().stream().filter(a -> a.state() == s).count());
        }
        return Map.of("total", actions.size(), "byState", counts);
    }
}
