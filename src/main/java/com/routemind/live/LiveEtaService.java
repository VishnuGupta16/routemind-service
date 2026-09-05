package com.routemind.live;

import com.routemind.live.Live.RiskLevel;
import com.routemind.live.Live.TrafficFactor;
import com.routemind.live.TripPriorProvider.Prior;
import com.routemind.live.TripPriorProvider.TripContext;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FUSION — combines three independent signals into one prediction:
 *
 *   1. LIVE      remaining distance ÷ smoothed speed, from the Kafka GPS stream
 *   2. PRIOR     what historically happens to trips like this (keyed or k-NN similar)
 *   3. TRAFFIC   the historical corridor profile for this office and hour
 *
 * Neither signal alone is good enough: GPS is noisy in the first minutes of a trip and
 * knows nothing about the jam ahead; history can't see today. So the weight shifts from
 * prior to live as the trip progresses and GPS samples accumulate:
 *
 *   w = min(1, samples/5) × (0.3 + 0.7 × progress)
 *   predictedDelay = w × liveDelay + (1 − w) × priorDelay
 */
@Service
public class LiveEtaService {

    public record Prediction(long tripId,
                             double predictedDelayMin,
                             double liveComponentMin,
                             double priorComponentMin,
                             double liveWeight,
                             double trafficFactor,
                             double progress,
                             double confidence,
                             RiskLevel level,
                             int employeesAffected,
                             String basis) {}

    /** Static trip facts, loaded once per trip and cached for the journey. */
    private record TripFacts(long tripId, String vendor, String office, String shift,
                             String direction, java.time.LocalDate date,
                             double plannedKm, Instant plannedStart, Instant plannedEnd,
                             int employees) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final LivePositionStore positions;
    private final TripPriorProvider prior;
    private final TrafficProvider traffic;
    private final Map<Long, TripFacts> factsCache = new ConcurrentHashMap<>();

    public LiveEtaService(NamedParameterJdbcTemplate jdbc,
                          LivePositionStore positions,
                          TripPriorProvider prior,
                          TrafficProvider traffic) {
        this.jdbc = jdbc;
        this.positions = positions;
        this.prior = prior;
        this.traffic = traffic;
    }

    public Optional<Prediction> predict(long tripId, Instant now) {
        var stateOpt = positions.state(tripId);
        var factsOpt = facts(tripId);
        if (stateOpt.isEmpty() || factsOpt.isEmpty()) return Optional.empty();

        LivePositionStore.TripState s = stateOpt.get();
        TripFacts f = factsOpt.get();

        double plannedKm = Math.max(f.plannedKm(), 0.1);
        double progress = Math.min(s.coveredKm / plannedKm, 1.0);
        double remainingKm = Math.max(plannedKm - s.coveredKm, 0.0);

        int hour = f.plannedStart() == null ? 9
                : f.plannedStart().atZone(ZoneOffset.UTC).getHour();
        TrafficFactor tf = traffic.factorFor(f.office(),
                java.time.LocalTime.of(hour, 0));

        // ---- signal 1: live
        double speed = Math.max(s.smoothedSpeedKph(), 6.0);      // floor: crawling, not stopped
        double etaMin = (remainingKm / speed) * 60.0 * tf.factor();
        Instant predictedArrival = now.plusSeconds((long) (etaMin * 60));
        double liveDelay = f.plannedEnd() == null ? 0
                : Duration.between(f.plannedEnd(), predictedArrival).toMinutes();

        // ---- signal 2: prior
        double elapsedMin = s.firstSeen == null ? 0
                : Duration.between(s.firstSeen, now).toMinutes();
        double plannedDuration = f.plannedStart() == null || f.plannedEnd() == null ? 0
                : Duration.between(f.plannedStart(), f.plannedEnd()).toMinutes();

        Prior p = prior.priorFor(new TripContext(tripId, f.vendor(), f.office(), f.shift(),
                f.direction(), f.date(), hour, f.date().getDayOfWeek().getValue(),
                plannedKm, plannedDuration, progress, elapsedMin, s.smoothedSpeedKph()));

        // ---- fuse
        double sampleTrust = Math.min(1.0, s.samples / 5.0);
        double w = sampleTrust * (0.3 + 0.7 * progress);
        double predicted = w * liveDelay + (1 - w) * p.expectedDelayMin();

        double confidence = Sql.round2(Math.min(0.95,
                0.3 + 0.4 * sampleTrust + 0.3 * p.confidence()));
        RiskLevel level = predicted >= 20 ? RiskLevel.HIGH
                : predicted >= 10 ? RiskLevel.MEDIUM : RiskLevel.LOW;

        String basis = String.format(
                "live %.0fmin (%.1fkm left @ %.0fkm/h) × traffic %.2f, weighted %.0f%% | "
                        + "prior %.0fmin [%s]",
                liveDelay, remainingKm, speed, tf.factor(), w * 100,
                p.expectedDelayMin(), p.basis());

        return Optional.of(new Prediction(tripId, Sql.round1(predicted),
                Sql.round1(liveDelay), Sql.round1(p.expectedDelayMin()), Sql.round2(w),
                Sql.round2(tf.factor()), Sql.round2(progress), confidence, level,
                f.employees(), basis));
    }

    private Optional<TripFacts> facts(long tripId) {
        TripFacts cached = factsCache.get(tripId);
        if (cached != null) return Optional.of(cached);
        String sql = """
                SELECT trip_id, vendor, office, shift_type, trip_direction, trip_date,
                       coalesce(planned_km, 0) AS planned_km, planned_start, planned_end,
                       coalesce(planned_employee_cnt, 0) AS emp
                FROM trips WHERE trip_id = :id
                """;
        try {
            TripFacts f = jdbc.query(sql, new MapSqlParameterSource("id", tripId), rs -> {
                if (!rs.next()) return null;
                var ps = rs.getTimestamp("planned_start");
                var pe = rs.getTimestamp("planned_end");
                return new TripFacts(rs.getLong("trip_id"), rs.getString("vendor"),
                        rs.getString("office"), rs.getString("shift_type"),
                        rs.getString("trip_direction"), rs.getDate("trip_date").toLocalDate(),
                        rs.getDouble("planned_km"),
                        ps == null ? null : ps.toInstant(), pe == null ? null : pe.toInstant(),
                        rs.getInt("emp"));
            });
            if (f != null) factsCache.put(tripId, f);
            return Optional.ofNullable(f);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Map<String, Object> status() {
        return Map.of("priorStrategy", prior.strategy(),
                "trafficProvider", traffic.name(),
                "positionProvider", positions.name(),
                "tripsCached", factsCache.size());
    }
}
