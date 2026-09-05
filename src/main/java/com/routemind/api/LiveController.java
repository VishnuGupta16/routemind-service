package com.routemind.api;

import com.routemind.live.Live.TripRisk;
import com.routemind.live.LiveAlertService;
import com.routemind.live.LiveAlertService.LiveAlert;
import com.routemind.live.LiveEtaService;
import com.routemind.live.LiveRiskService;
import com.routemind.live.ReplaySimulator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/** Predictive + live layer: what is going to fail, before it does. */
@RestController
@RequestMapping("/api/live")
@CrossOrigin
public class LiveController {

    private final LiveRiskService risk;
    private final LiveAlertService alerts;
    private final ObjectProvider<LiveEtaService> eta;
    private final ReplaySimulator replay;

    public LiveController(LiveRiskService risk, LiveAlertService alerts,
                          ObjectProvider<LiveEtaService> eta, ReplaySimulator replay) {
        this.risk = risk;
        this.alerts = alerts;
        this.eta = eta;
        this.replay = replay;
    }

    /** Pre-trip briefing: which of tomorrow's trips are likely to fail. */
    @GetMapping("/risk")
    public List<TripRisk> risk(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                               @RequestParam(required = false) String businessUnit,
                               @RequestParam(defaultValue = "20") int limit) {
        return risk.riskFor(day, businessUnit, limit);
    }

    /** Fused prediction for one in-flight trip (live GPS + prior + traffic). */
    @GetMapping("/predict/{tripId}")
    public Object predict(@PathVariable long tripId) {
        LiveEtaService svc = eta.getIfAvailable();
        if (svc == null) return Map.of("error", "live feed disabled");
        return svc.predict(tripId, Instant.now())
                .map(Object.class::cast)
                .orElse(Map.of("error", "no live data for trip " + tripId));
    }

    /** Alerts raised in-flight. */
    @GetMapping("/alerts")
    public List<LiveAlert> alerts(@RequestParam(defaultValue = "50") int limit) {
        return alerts.recent(limit);
    }

    /** Server-sent events — the dashboard subscribes here for push alerts. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        Consumer<LiveAlert> consumer = a -> {
            try {
                emitter.send(SseEmitter.event().name("alert").data(a));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };
        alerts.subscribe(consumer);
        emitter.onCompletion(() -> alerts.unsubscribe(consumer));
        emitter.onTimeout(() -> alerts.unsubscribe(consumer));
        return emitter;
    }

    // ------------------------------------------------------------- replay demo
    /**
     * Replay a historical day as if it were happening now, so the whole live path
     * (ingest → fuse → alert → SSE) can be demonstrated without a GPS feed.
     * Progress and timing are real; only the geography is synthesised.
     *
     * POST /api/live/replay/start?day=2026-07-15&speed=60&limit=25
     */
    @PostMapping("/replay/start")
    public ReplaySimulator.ReplayStatus startReplay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(defaultValue = "60") int speed,
            @RequestParam(defaultValue = "25") int limit) {
        return replay.start(day, speed, limit);
    }

    @PostMapping("/replay/stop")
    public ReplaySimulator.ReplayStatus stopReplay() { return replay.stop(); }

    @GetMapping("/replay/status")
    public ReplaySimulator.ReplayStatus replayStatus() { return replay.status(); }

    /** Which mode the predictive layer is running in. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(risk.status());
        out.put("alerting", alerts.stats());
        LiveEtaService svc = eta.getIfAvailable();
        out.put("fusion", svc == null ? Map.of("enabled", false) : svc.status());
        out.put("replay", replay.status());
        return out;
    }
}
