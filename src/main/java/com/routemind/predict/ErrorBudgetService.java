package com.routemind.predict;

import com.routemind.metrics.model.Models.Projection;
import com.routemind.metrics.model.Models.Status;
import com.routemind.metrics.spi.MetricDefinition;
import com.routemind.metrics.spi.Sql;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Turns a lagging metric into an ANTICIPATORY one — with arithmetic, no ML.
 *
 * If the SLA allows N failures for the period and you have burned 62% of that
 * budget in 27% of the period, you are on pace to breach, and we can name the day.
 */
@Service
public class ErrorBudgetService {

    /**
     * @param value        metric value so far this period (percent)
     * @param target       SLA target (percent)
     * @param periodStart  start of the period being tracked
     * @param periodEnd    end of the period being tracked
     * @param asOf         "today" within that period
     */
    public Projection project(double value, double target, MetricDefinition.Direction dir,
                              LocalDate periodStart, LocalDate periodEnd, LocalDate asOf) {

        long total = Math.max(ChronoUnit.DAYS.between(periodStart, periodEnd) + 1, 1);
        long elapsed = Math.min(Math.max(ChronoUnit.DAYS.between(periodStart, asOf) + 1, 1), total);
        double elapsedFraction = (double) elapsed / total;

        // failure budget, expressed in percentage points
        boolean higherBetter = dir == MetricDefinition.Direction.HIGHER_IS_BETTER;
        double allowedFailPct = higherBetter ? (100.0 - target) : target;
        double actualFailPct  = higherBetter ? (100.0 - value)  : value;

        double budgetUsed = allowedFailPct <= 0 ? 0
                : (actualFailPct / allowedFailPct) * 100.0 * elapsedFraction;
        double burnRate = elapsedFraction <= 0 || allowedFailPct <= 0 ? 0
                : (actualFailPct / allowedFailPct);

        // simple pace projection: today's rate holds for the rest of the period
        double projected = value;

        LocalDate breachDate = null;
        if (burnRate > 1.0) {
            double daysUntil = total / burnRate;
            LocalDate d = periodStart.plusDays((long) Math.floor(daysUntil));
            breachDate = d.isAfter(periodEnd) ? null : d;
        }

        Status status;
        boolean breaching = higherBetter ? projected < target : projected > target;
        if (breaching) status = Status.BREACH;
        else if (burnRate > 0.9) status = Status.AT_RISK;
        else status = Status.OK;

        return new Projection(Sql.round1(projected), Sql.round1(Math.min(budgetUsed, 999)),
                Sql.round2(burnRate), breachDate, status);
    }
}
