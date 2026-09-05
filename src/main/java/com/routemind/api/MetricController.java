package com.routemind.api;

import com.routemind.metrics.MetricRepository;
import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.model.Models.TableCount;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class MetricController {

    private final MetricService metrics;
    private final MetricRepository repo;
    private final com.routemind.metrics.PeerComparisonService peers;

    public MetricController(MetricService metrics, MetricRepository repo,
                            com.routemind.metrics.PeerComparisonService peers) {
        this.metrics = metrics;
        this.repo = repo;
        this.peers = peers;
    }

    /**
     * GET /api/metrics/{id}/peers — the third reference point.
     * Ranks every business unit on this metric so a number can be judged against the estate.
     */
    @GetMapping("/metrics/{id}/peers")
    public com.routemind.metrics.PeerComparisonService.PeerComparison peers(
            @PathVariable String id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return peers.acrossBusinessUnits(id, from, to, businessUnit);
    }

    /**
     * GET /api/metrics/ota?from=2026-07-01&to=2026-07-31
     * Any registered metric id works — ota, cost_per_trip, no_show_rate,
     * experience, safety_alerts_per_1k, seat_utilisation, ev_share.
     */
    @GetMapping("/metrics/{id}")
    public ResponseEntity<MetricWithContext> metric(
            @PathVariable String id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String businessUnit) {
        return metrics.metric(id, from, to, businessUnit)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/health/data — verifies the Postgres load. */
    @GetMapping("/health/data")
    public Map<String, Object> dataHealth() {
        List<TableCount> counts = repo.tableCounts();
        boolean ok = counts.stream().allMatch(c -> c.rows() > 0);
        return Map.of("status", ok ? "OK" : "EMPTY",
                "tables", counts,
                "businessUnits", repo.businessUnits(),
                "dateRange", repo.dateRange());
    }
}
