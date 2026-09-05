package com.routemind.rules;

import com.routemind.metrics.spi.MetricDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declarative RuleSet — targets, trigger conditions and persona routing all
 * live in application.yml. Onboarding a new tenant is a config change, never code.
 */
@ConfigurationProperties(prefix = "routemind")
public class RuleSetProperties implements MetricDefinition.Targets {

    private Sla sla = new Sla();
    private Attribution attribution = new Attribution();
    private Trigger trigger = new Trigger();
    /** metricId -> alerting target (contractual SLA, or calibrated near observed normal) */
    private Map<String, Double> targets = new LinkedHashMap<>();
    /**
     * metricId -> aspirational goal. Reported as a "goal gap" to the strategic persona
     * but never alerted on, so a long-range ambition (EV 25%) doesn't fire daily forever.
     */
    private Map<String, Double> goals = new LinkedHashMap<>();
    /**
     * PER-METRIC product exclusions: metricId -> product types that metric must ignore.
     *
     * Exclusion is per metric, not global, because relevance differs by metric. SPOT_2.0
     * is a RENTAL cab: it is booked for a job rather than scheduled against a pickup
     * commitment, so it has no meaningful on-time target and must be left out of OTA —
     * but it still costs money, has a vendor, burns fuel and can raise safety alerts, so
     * it belongs in every other metric.
     */
    private Map<String, List<String>> metricExclusions = new LinkedHashMap<>();
    /** persona -> metric ids it cares about */
    private Map<String, List<String>> personas = new LinkedHashMap<>();

    @Override
    public double targetFor(String metricId, double fallback) {
        return targets.getOrDefault(metricId, fallback);
    }

    public Sla getSla() { return sla; }
    public void setSla(Sla v) { this.sla = v; }
    public Attribution getAttribution() { return attribution; }
    public void setAttribution(Attribution v) { this.attribution = v; }
    public Trigger getTrigger() { return trigger; }
    public void setTrigger(Trigger v) { this.trigger = v; }
    public Map<String, Double> getTargets() { return targets; }
    public void setTargets(Map<String, Double> v) { this.targets = v; }
    public Map<String, Double> getGoals() { return goals; }
    public void setGoals(Map<String, Double> v) { this.goals = v; }

    /** Aspirational goal for a metric, if one is configured. */
    public Double goalFor(String metricId) { return goals.get(metricId); }

    public Map<String, List<String>> getMetricExclusions() { return metricExclusions; }
    public void setMetricExclusions(Map<String, List<String>> v) { this.metricExclusions = v; }

    /** Product types this specific metric must ignore (empty = include everything). */
    public List<String> exclusionsFor(String metricId) {
        return metricExclusions.getOrDefault(metricId, List.of());
    }
    public Map<String, List<String>> getPersonas() { return personas; }
    public void setPersonas(Map<String, List<String>> v) { this.personas = v; }

    public static class Sla {
        private int otaWindowMinutes = 10;
        private double atRiskMargin = 2.0;
        /**
         * Winsorising ceiling for delay_minutes. The dataset contains delays up to
         * 10,644 minutes (7.4 days) — data-entry artefacts, not journeys. They don't
         * affect OTA (a threshold count) but they inflate MEAN delay by ~15%, which
         * would corrupt every prediction built on it. Delays are capped at this value
         * rather than dropped, so the row still counts as late.
         */
        private int maxCredibleDelayMinutes = 240;

        public int getOtaWindowMinutes() { return otaWindowMinutes; }
        public void setOtaWindowMinutes(int v) { this.otaWindowMinutes = v; }
        public double getAtRiskMargin() { return atRiskMargin; }
        public void setAtRiskMargin(double v) { this.atRiskMargin = v; }
        public int getMaxCredibleDelayMinutes() { return maxCredibleDelayMinutes; }
        public void setMaxCredibleDelayMinutes(int v) { this.maxCredibleDelayMinutes = v; }
    }

    public static class Attribution {
        private int topN = 3;
        public int getTopN() { return topN; }
        public void setTopN(int v) { this.topN = v; }
    }

    public static class Trigger {
        /** a fall of at least this many units vs prior period raises a finding */
        private double trendDropUnits = 3.0;
        private int cooldownDays = 3;
        /** cron for the proactive scan */
        private String cron = "0 0 7 * * *";
        private boolean enabled = true;
        public double getTrendDropUnits() { return trendDropUnits; }
        public void setTrendDropUnits(double v) { this.trendDropUnits = v; }
        public int getCooldownDays() { return cooldownDays; }
        public void setCooldownDays(int v) { this.cooldownDays = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { this.cron = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
