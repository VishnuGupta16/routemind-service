package com.routemind.diagnose;

import com.routemind.metrics.MetricService;
import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.MetricDefinition.Direction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which metrics are degrading, HOW they are degrading, and WHY — for the transport manager.
 *
 * The facilities head gets a monthly narrative. The transport manager gets the opposite:
 * fast operational signals, ranked by urgency, each with the reason attached so they can act
 * without opening a dashboard. This service produces exactly that for EVERY registered
 * metric, not just OTA, by asking three questions of each one's recent time series:
 *
 *   1. IS it degrading?   value the wrong side of, or moving away from, its reference.
 *   2. HOW?               a SUDDEN step (the latest bucket breaks from a stable run) or an
 *                         INCREMENTAL slide (a persistent worsening slope). These need
 *                         different responses — a step is an incident to chase now, a slide
 *                         is a trend to get ahead of — so the shape is part of the signal.
 *   3. WHY?               the slice driving it, from the metric's own attribution.
 *
 * The shape classification is deliberately model-free: bucket the window, look at the slope
 * and at how far the last bucket sits from the run before it. A manager does not need a
 * change-point algorithm; they need "this broke on Tuesday" told apart from "this has been
 * sliding for three weeks".
 */
@Service
public class MetricDegradationService {

    /** Number of equal time buckets the window is split into to see the shape. */
    private static final int BUCKETS = 6;

    /** A bucket needs at least this many samples to be trusted in the trend. */
    private static final long MIN_BUCKET_SAMPLE = 50;

    private final MetricService metrics;

    public MetricDegradationService(MetricService metrics) { this.metrics = metrics; }

    /** Re-exported so callers depend on one enum, not two. */
    public enum Shape { SUDDEN, INCREMENTAL, STABLE, IMPROVING, INSUFFICIENT_DATA }

    private static Shape map(Trend.Shape s) {
        return switch (s) {
            case SUDDEN -> Shape.SUDDEN;
            case INCREMENTAL -> Shape.INCREMENTAL;
            case STABLE -> Shape.STABLE;
            case IMPROVING -> Shape.IMPROVING;
            case INSUFFICIENT_DATA -> Shape.INSUFFICIENT_DATA;
        };
    }

    public record Bucket(LocalDate from, LocalDate to, double value, long sampleSize) {}

    public record Signal(String metricId,
                         String displayName,
                         String unit,
                         Shape shape,
                         String status,          // MET | AT_RISK | BREACH (period as a whole)
                         double latest,
                         double target,
                         Double changePerBucket,  // slope; sign is in the worsening direction
                         double latestVsRun,      // how far the last bucket is from the prior run
                         double urgency,          // 0..100, for ranking the manager's queue
                         String worstSlice,       // the dimension value driving it
                         String worstSliceDimension,
                         String reason,           // one plain sentence: what and why
                         List<Bucket> series) {}

    /**
     * Every metric that is degrading AND close enough to its target to matter, most urgent
     * first.
     *
     * Shape alone is not a reason to alert. A metric drifting from 12% to 10.9% against a
     * 9% floor is sliding in the worsening direction and still comfortably fine — reporting
     * that as "a trend to get ahead of before it breaches" is noise, and noise is what
     * makes an alert stream get ignored. So a slide only counts once the value is within
     * {@link #ALERT_BAND_PCT} of the target, or already the wrong side of it.
     *
     * A SUDDEN step is exempt: a sharp break from a stable run is worth knowing about even
     * from a healthy level, because it is usually an incident rather than a drift.
     */
    public List<Signal> degrading(LocalDate from, LocalDate to, String businessUnit) {
        List<Signal> out = new ArrayList<>();
        for (String id : metrics.metricIds()) {
            signal(id, from, to, businessUnit).ifPresent(s -> {
                boolean worsening = s.shape() == Shape.SUDDEN || s.shape() == Shape.INCREMENTAL;
                // A SUDDEN step is worth knowing about from any level — it is an incident.
                // A slide only matters once it is close to the target it would breach.
                boolean movingBadly = worsening
                        && (s.shape() == Shape.SUDDEN || nearTarget(s));
                // And a metric already OUTSIDE its target belongs on the board whatever its
                // shape: "below contract but recovering" is still below contract, and
                // hiding it because the trend is kind would lose a real breach.
                boolean alreadyOut = "BREACH".equals(s.status());
                if (movingBadly || alreadyOut) out.add(s);
            });
        }
        out.sort(Comparator.comparingDouble(Signal::urgency).reversed());
        return out;
    }

    /**
     * How close to the target a sliding metric must be before the slide is worth raising,
     * as a percentage of the target itself — proportional, so it works for a 4.85 rating
     * and a ₹1,400 cost without a per-metric threshold.
     */
    private static final double ALERT_BAND_PCT = 10.0;

    /** Already breaching, or within the band where a continued slide would breach. */
    static boolean nearTarget(Signal s) {
        if (!"MET".equals(s.status()) && !"OK".equals(s.status())) return true;
        double target = s.target();
        if (target == 0) return true;
        double band = Math.abs(target) * (ALERT_BAND_PCT / 100.0);
        return Math.abs(s.latest() - target) <= band;
    }

    /** Every metric with its shape, degrading or not — the fuller operational board. */
    public List<Signal> all(LocalDate from, LocalDate to, String businessUnit) {
        List<Signal> out = new ArrayList<>();
        for (String id : metrics.metricIds()) {
            signal(id, from, to, businessUnit).ifPresent(out::add);
        }
        out.sort(Comparator.comparingDouble(Signal::urgency).reversed());
        return out;
    }

    // ------------------------------------------------------------------ one metric

    private java.util.Optional<Signal> signal(String id, LocalDate from, LocalDate to,
                                              String bu) {
        MetricWithContext whole = metrics.metric(id, from, to, bu).orElse(null);
        if (whole == null) return java.util.Optional.empty();

        boolean higherBetter = Direction.HIGHER_IS_BETTER.name().equals(whole.direction());
        List<Bucket> series = bucketise(id, from, to, bu);
        List<Bucket> usable = series.stream()
                .filter(b -> b.sampleSize() >= MIN_BUCKET_SAMPLE).toList();

        if (usable.size() < 3) {
            return java.util.Optional.of(signalOf(whole, higherBetter,
                    Shape.INSUFFICIENT_DATA, null, 0, 0, series));
        }

        double[] values = usable.stream().mapToDouble(Bucket::value).toArray();
        Trend.Result trend = Trend.classify(values, higherBetter);
        Shape shape = map(trend.shape());

        double urgency = urgency(whole, shape, trend.stepZ());
        return java.util.Optional.of(signalOf(whole, higherBetter, shape,
                Trend.ols(values), trend.worsenStep(), urgency, series));
    }

    private double urgency(MetricWithContext m, Shape shape, double stepZ) {
        double u = switch (m.status()) {                 // where it already sits
            case BREACH -> 50;
            case AT_RISK -> 25;
            case OK -> 5;
        };
        u += switch (shape) {                            // how it is moving
            case SUDDEN -> 30 + Math.min(15, stepZ * 3);
            case INCREMENTAL -> 20;
            default -> 0;
        };
        // a metric this persona can actually move ranks above one they can only report
        if (m.metric().equals("ota") || m.metric().equals("worst_product_ota")
                || m.metric().equals("no_show_rate")) u += 5;
        return Math.min(100, Math.round(u * 10) / 10.0);
    }

    private Signal signalOf(MetricWithContext m, boolean higherBetter, Shape shape,
                            Double slopePerBucket, double worsenStep, double urgency,
                            List<Bucket> series) {
        String slice = null, sliceDim = null;
        if (m.topContributors() != null && !m.topContributors().isEmpty()) {
            slice = m.topContributors().get(0).member();
            sliceDim = m.attributionDimension();
        }
        return new Signal(m.metric(), m.displayName(), m.unit(), shape, m.status().name(),
                round(m.value()), round(m.target()), slopePerBucket, round(worsenStep),
                urgency, slice, sliceDim, reason(m, shape, slice, sliceDim), series);
    }

    /** The one sentence a manager reads. States only the shape and the driving slice. */
    private String reason(MetricWithContext m, Shape shape, String slice, String sliceDim) {
        String name = m.displayName();
        String where = slice == null ? ""
                : String.format(" Largest contributor: %s (%s).", slice, sliceDim);
        return switch (shape) {
            case SUDDEN -> String.format(
                    "%s stepped down suddenly to %s against a target of %s — treat as an "
                            + "incident, not a trend.%s",
                    name, fmt(m.value(), m.unit()), fmt(m.target(), m.unit()), where);
            case INCREMENTAL -> String.format(
                    "%s has been sliding across the window to %s (target %s) — a trend to "
                            + "get ahead of before it breaches.%s",
                    name, fmt(m.value(), m.unit()), fmt(m.target(), m.unit()), where);
            case IMPROVING -> String.format("%s is improving to %s.", name,
                    fmt(m.value(), m.unit()));
            case STABLE -> String.format("%s is stable at %s.", name, fmt(m.value(), m.unit()));
            case INSUFFICIENT_DATA -> String.format(
                    "%s does not have enough recent volume to judge a trend.", name);
        };
    }

    // ------------------------------------------------------------------ time series

    private List<Bucket> bucketise(String id, LocalDate from, LocalDate to, String bu) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        long per = Math.max(1, days / BUCKETS);
        List<Bucket> out = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate bEnd = cursor.plusDays(per - 1);
            if (bEnd.isAfter(to)) bEnd = to;
            LocalDate fCursor = cursor, fEnd = bEnd;
            metrics.metric(id, cursor, bEnd, bu).ifPresent(mc ->
                    out.add(new Bucket(fCursor, fEnd, round(mc.value()), mc.sampleSize())));
            cursor = bEnd.plusDays(1);
        }
        return out;
    }

    // -------------------------------------------------------------------- maths

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String fmt(double v, String unit) {
        return switch (unit == null ? "" : unit) {
            case "percent" -> String.format("%.1f%%", v);
            case "currency" -> String.format("₹%,.0f", v);
            case "rating" -> String.format("%.2f", v);
            default -> String.format("%,.1f", v);
        };
    }
}
