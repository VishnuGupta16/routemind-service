package com.routemind.schedule;

import com.routemind.persona.Persona;
import com.routemind.persona.PersonaRouter;
import com.routemind.persona.PersonaRouter.PersonaBundle;
import com.routemind.rules.Finding;
import com.routemind.rules.RuleSetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "It triggers on its own." Runs on a schedule, evaluates every persona, and
 * publishes only findings that are NEW (cooldown-suppressed otherwise), so the
 * system nags once rather than daily.
 */
@Component
public class ProactiveScanner {

    private static final Logger log = LoggerFactory.getLogger(ProactiveScanner.class);

    private final PersonaRouter router;
    private final RuleSetProperties rules;
    private final com.routemind.action.ActionService actions;

    /** dedupeKey -> last time we alerted. In production this is a table. */
    private final Map<String, LocalDate> lastAlerted = new ConcurrentHashMap<>();
    /** latest published findings per persona, served to the UI alert feed. */
    private final Map<String, List<Finding>> feed = new ConcurrentHashMap<>();
    private volatile LocalDate lastRun;

    public ProactiveScanner(PersonaRouter router, RuleSetProperties rules,
                            com.routemind.action.ActionService actions) {
        this.router = router;
        this.rules = rules;
        this.actions = actions;
    }

    /**
     * The bean always exists (controllers depend on it); the SCHEDULE is what the
     * `routemind.trigger.enabled` flag turns off.
     */
    @Scheduled(cron = "${routemind.trigger.cron:0 0 7 * * *}")
    public void scan() {
        if (!rules.getTrigger().isEnabled()) {
            log.debug("Proactive scan skipped — routemind.trigger.enabled=false");
            return;
        }
        scanFor(LocalDate.now());
    }

    /** Exposed so the demo can trigger a scan for any date without waiting for cron. */
    public Map<String, List<Finding>> scanFor(LocalDate asOf) {
        LocalDate to = asOf;
        LocalDate from = to.minusDays(6);            // rolling week
        int cooldown = rules.getTrigger().getCooldownDays();

        for (Persona p : Persona.values()) {
            PersonaBundle b = router.bundle(p, from, to, null, 5);
            List<Finding> fresh = new ArrayList<>();
            for (Finding f : b.findings()) {
                LocalDate last = lastAlerted.get(f.dedupeKey());
                boolean suppressed = last != null && last.plusDays(cooldown).isAfter(to);
                if (!suppressed) {
                    lastAlerted.put(f.dedupeKey(), to);
                    fresh.add(f);
                    // sense -> reason -> ACT: put a concrete proposal on a human's desk
                    actions.proposeFrom(f);
                }
            }
            feed.put(p.name(), fresh);
            if (!fresh.isEmpty()) {
                log.info("[{}] {} new finding(s): {}", p.name(), fresh.size(),
                        fresh.stream().map(Finding::metricId).toList());
            }
        }
        lastRun = to;
        return Map.copyOf(feed);
    }

    public List<Finding> feedFor(Persona p) {
        return feed.getOrDefault(p.name(), List.of());
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastRun", lastRun);
        m.put("cron", rules.getTrigger().getCron());
        m.put("cooldownDays", rules.getTrigger().getCooldownDays());
        m.put("suppressedKeys", lastAlerted.size());
        m.put("pending", feed.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().size())));
        return m;
    }
}
