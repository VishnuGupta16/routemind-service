package com.routemind.report;

import java.time.LocalDate;

/**
 * A report generator is a plugin, exactly like a {@code MetricDefinition}.
 *
 * Implement this, annotate the class {@code @Component}, and add a row to
 * {@code alert_definition} with a matching {@code generator_key}. The scheduler and the
 * notification engine find it by key and need no change — which is what keeps "add a
 * second persona" a one-class job rather than a rewrite.
 */
public interface ReportGenerator {

    /** Must match alert_definition.generator_key. */
    String key();

    /** Must match persona.code. */
    String personaCode();

    /**
     * Produce the report for a period.
     *
     * Implementations must return a report even when nothing is wrong — with
     * {@code actionable = false}. "We looked and there was nothing to say" is evidence, and
     * suppressing it entirely makes a quiet month indistinguishable from a broken job. The
     * decision to actually SEND is taken later, from {@code actionable} and the alert's
     * {@code send_only_if_actionable} flag.
     */
    GeneratedReport generate(Request request);

    /**
     * @param compareStart may be null when there is no prior window to compare against —
     *                     the first period after go-live, for instance. Generators must
     *                     handle that by reporting the value with a non-prior reference
     *                     (an SLA or a target) rather than inventing a trend.
     */
    record Request(LocalDate periodStart,
                   LocalDate periodEnd,
                   LocalDate compareStart,
                   LocalDate compareEnd,
                   String businessUnit) {

        /** The immediately preceding window of the same length. */
        public static Request endingAt(LocalDate periodEnd, int lookbackDays,
                                       int compareDays, String businessUnit) {
            LocalDate start = periodEnd.minusDays(lookbackDays - 1L);
            LocalDate cmpEnd = start.minusDays(1);
            LocalDate cmpStart = compareDays <= 0 ? null : cmpEnd.minusDays(compareDays - 1L);
            return new Request(start, periodEnd, cmpStart,
                    compareDays <= 0 ? null : cmpEnd, businessUnit);
        }

        public boolean hasComparison() { return compareStart != null && compareEnd != null; }
    }
}
