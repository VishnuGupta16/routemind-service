package com.routemind.metrics;

import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.MetricDefinition.Contribution;
import com.routemind.metrics.spi.MetricDefinition.MetricPoint;

import java.util.List;

/**
 * Deterministic sentence builder. Always available, costs nothing, and is the
 * fallback whenever the LLM is disabled or fails — so the product never has a
 * blank space where an explanation should be.
 */
public final class Narratives {

    private Narratives() {}

    public static String headline(MetricDefinition def, MetricPoint cur, double target,
                                  Double prior, Double vsPrior,
                                  List<Contribution> contributors, Status status) {
        if (cur.isEmpty()) return def.displayName() + ": no data for this period.";

        StringBuilder sb = new StringBuilder();
        sb.append(def.displayName()).append(" is ").append(fmt(def, cur.value()))
          .append(" across ").append(String.format("%,d", cur.sampleSize()))
          .append(" records, against a target of ").append(fmt(def, target));

        if (prior != null) {
            String dir = vsPrior == null || vsPrior == 0 ? "flat vs"
                    : (vsPrior > 0 ? "up from" : "down from");
            sb.append(" (").append(dir).append(' ').append(fmt(def, prior))
              .append(" last period)");
        }
        sb.append(". ");

        if (!contributors.isEmpty()) {
            Contribution top = contributors.get(0);
            sb.append(top.member()).append(" accounts for ").append(top.pct())
              .append("% of the ").append(def.attributionDimension().equals("event type")
                      ? "alerts" : "impact").append(". ");
        }

        switch (status) {
            case BREACH -> sb.append("Below target — action needed.");
            case AT_RISK -> sb.append("Close to target — worth watching.");
            case OK -> sb.append("Within target.");
        }
        return sb.toString();
    }

    public static String fmt(MetricDefinition def, double v) {
        return switch (def.unit()) {
            case "percent" -> v + "%";
            case "currency" -> "₹" + String.format("%,.0f", v);
            case "rating" -> String.format("%.2f", v) + "/5";
            default -> String.valueOf(v);
        };
    }
}
