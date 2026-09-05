package com.routemind.diagnose;

/**
 * The pure maths behind degradation shape — no Spring, no database, so it can be tested
 * directly on a series of numbers.
 *
 * Two questions of a short time series, both answered model-free because a transport
 * manager does not need a change-point algorithm, they need "this broke on Tuesday" told
 * apart from "this has been sliding for three weeks":
 *
 *   slope  — is it drifting the wrong way across the whole window?
 *   step   — does the LAST bucket break from the run before it? (spike, not drift)
 *
 * Both are expressed in the WORSENING direction, so a positive number always means "worse",
 * whatever the metric's polarity. When a step and a slide co-occur the step wins: an
 * incident that just happened is what must be acted on first.
 */
public final class Trend {

    private Trend() {}

    public enum Shape { SUDDEN, INCREMENTAL, STABLE, IMPROVING, INSUFFICIENT_DATA }

    public record Result(Shape shape, double worsenSlope, double stepZ, double worsenStep) {}

    /**
     * @param series       values oldest-to-newest, only the buckets with enough sample
     * @param higherBetter true for OTA/utilisation, false for cost/no-show/safety
     */
    public static Result classify(double[] series, boolean higherBetter) {
        if (series.length < 3) {
            return new Result(Shape.INSUFFICIENT_DATA, 0, 0, 0);
        }

        double slope = ols(series);
        double worsenSlope = higherBetter ? -slope : slope;

        double[] run = new double[series.length - 1];
        System.arraycopy(series, 0, run, 0, run.length);
        double runMean = mean(run);
        double runSd = Math.max(sd(run), 1e-9);
        double last = series[series.length - 1];
        double worsenStep = higherBetter ? (runMean - last) : (last - runMean);
        double stepZ = worsenStep / runSd;

        // ignore sub-noise wiggles: a wiggle smaller than 2% of the level is not a signal
        double scale = meanAbs(series);
        double meaningful = Math.max(0.5, scale * 0.02);

        boolean stepped = stepZ >= 2.0 && worsenStep >= meaningful;
        boolean sliding = worsenSlope >= meaningful * 0.5;

        Shape shape;
        if (stepped) shape = Shape.SUDDEN;              // an incident, chase it now
        else if (sliding) shape = Shape.INCREMENTAL;    // a trend, get ahead of it
        else if (worsenSlope <= -meaningful * 0.5) shape = Shape.IMPROVING;
        else shape = Shape.STABLE;

        return new Result(shape, round(worsenSlope), round(stepZ), round(worsenStep));
    }

    /** OLS slope of value over index 0..n-1. */
    static double ols(double[] y) {
        int n = y.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += i; sy += y[i]; sxx += (double) i * i; sxy += (double) i * y[i];
        }
        double denom = (double) n * sxx - sx * sx;
        return denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
    }

    static double mean(double[] v) {
        if (v.length == 0) return 0;
        double s = 0; for (double x : v) s += x; return s / v.length;
    }

    static double sd(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.length - 1));
    }

    static double meanAbs(double[] v) {
        if (v.length == 0) return 1;
        double s = 0; for (double x : v) s += Math.abs(x); return s / v.length;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
