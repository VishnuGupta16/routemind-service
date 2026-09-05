package com.routemind.live;

import com.routemind.live.Live.RiskLevel;
import com.routemind.live.LiveEtaService.Prediction;
import com.routemind.rules.RuleSetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-flight alerting. Fires WHILE the trip is running, so there is still time to act.
 *
 * Two things stop it becoming noise:
 *   - a trip alerts once, and only re-alerts if the predicted delay grows materially
 *   - low-confidence predictions are held back until the fusion is sure enough
 */
@Service
public class LiveAlertService {

    public record LiveAlert(long tripId,
                            RiskLevel level,
                            double predictedDelayMin,
                            double confidence,
                            int employeesAffected,
                            Instant raisedAt,
                            String message,
                            String recommendedAction,
                            String basis) {}

    private static final Logger log = LoggerFactory.getLogger(LiveAlertService.class);
    private static final double ESCALATION_STEP_MIN = 5.0;
    private static final double MIN_CONFIDENCE = 0.45;

    private final RuleSetProperties rules;
    private final Map<Long, Double> lastAlertedDelay = new ConcurrentHashMap<>();
    private final List<LiveAlert> recent = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<LiveAlert>> subscribers =
            new CopyOnWriteArrayList<>();

    public LiveAlertService(RuleSetProperties rules) { this.rules = rules; }

    /** Called for every GPS ping's prediction; decides whether a human hears about it. */
    public Optional<LiveAlert> consider(Prediction p) {
        double threshold = rules.getSla().getOtaWindowMinutes();

        if (p.predictedDelayMin() < threshold) return Optional.empty();
        if (p.confidence() < MIN_CONFIDENCE) return Optional.empty();

        Double last = lastAlertedDelay.get(p.tripId());
        boolean firstTime = last == null;
        boolean escalated = !firstTime && p.predictedDelayMin() >= last + ESCALATION_STEP_MIN;
        if (!firstTime && !escalated) return Optional.empty();

        lastAlertedDelay.put(p.tripId(), p.predictedDelayMin());

        LiveAlert a = new LiveAlert(p.tripId(), p.level(), p.predictedDelayMin(),
                p.confidence(), p.employeesAffected(), Instant.now(),
                message(p, firstTime), action(p), p.basis());

        recent.add(0, a);
        while (recent.size() > 200) recent.remove(recent.size() - 1);
        subscribers.forEach(s -> {
            try { s.accept(a); } catch (Exception ignored) { }
        });
        log.info("LIVE ALERT trip={} delay={}min level={} staff={}",
                a.tripId(), a.predictedDelayMin(), a.level(), a.employeesAffected());
        return Optional.of(a);
    }

    private String message(Prediction p, boolean firstTime) {
        return String.format(
                "%sTrip %d is predicted to arrive %.0f minutes late (%.0f%% confidence). "
                        + "%d employee(s) affected.",
                firstTime ? "" : "Escalating: ",
                p.tripId(), p.predictedDelayMin(), p.confidence() * 100,
                p.employeesAffected());
    }

    private String action(Prediction p) {
        if (p.level() == RiskLevel.HIGH) {
            return "Reassign affected employees to a cab on the same office/shift with "
                    + "spare seats, and notify the floor manager now.";
        }
        return "Notify riders of the revised pickup time and monitor the next few pings.";
    }

    /** Live feed for the UI (SSE). */
    public void subscribe(java.util.function.Consumer<LiveAlert> consumer) {
        subscribers.add(consumer);
    }

    public void unsubscribe(java.util.function.Consumer<LiveAlert> consumer) {
        subscribers.remove(consumer);
    }

    public List<LiveAlert> recent(int limit) {
        return recent.stream().limit(limit).toList();
    }

    public Map<String, Object> stats() {
        return Map.of("tripsAlerted", lastAlertedDelay.size(),
                "recentAlerts", recent.size(),
                "subscribers", subscribers.size(),
                "minConfidence", MIN_CONFIDENCE);
    }
}
