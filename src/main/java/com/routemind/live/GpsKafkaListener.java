package com.routemind.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes the vehicle GPS stream. Kafka key = tripId, so every ping for a trip lands
 * on one partition and is processed in order — no reordering logic needed.
 *
 * Each ping updates in-memory state and re-runs the fusion prediction; the alert
 * service decides whether that prediction is worth telling a human about.
 */
@Component
@ConditionalOnProperty(prefix = "routemind.live.kafka", name = "enabled", havingValue = "true")
public class GpsKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(GpsKafkaListener.class);

    private final LivePositionStore store;
    private final LiveEtaService eta;
    private final LiveAlertService alerts;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public GpsKafkaListener(LivePositionStore store, LiveEtaService eta, LiveAlertService alerts) {
        this.store = store;
        this.eta = eta;
        this.alerts = alerts;
    }

    @KafkaListener(
            topics = "${routemind.live.kafka.topic:cab.gps}",
            groupId = "${routemind.live.kafka.group-id:routemind-live}")
    public void onPing(GpsEvent event) {
        if (event == null || !event.valid()) {
            rejected.incrementAndGet();
            return;
        }
        accepted.incrementAndGet();
        store.accept(event);

        // fuse live + prior + traffic, then let the alert service apply thresholds
        eta.predict(event.tripId(), Instant.now()).ifPresent(alerts::consider);
    }

    public java.util.Map<String, Object> stats() {
        return java.util.Map.of("accepted", accepted.get(), "rejected", rejected.get());
    }
}
