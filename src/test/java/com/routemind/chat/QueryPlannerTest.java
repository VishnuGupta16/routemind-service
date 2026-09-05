package com.routemind.chat;

import com.routemind.chat.QueryPlanner.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The keyword floor must route correctly on its own, because it is what runs when no model
 * key is configured — and because these exact questions were misrouted before it existed.
 */
class QueryPlannerTest {

    private final QueryPlanner planner = new QueryPlanner(null);

    private Tool route(String q) { return planner.keywordPlan(q).tool(); }

    @Test
    @DisplayName("contract language routes to SLA compliance, not an OTA decomposition")
    void slaQuestion() {
        assertEquals(Tool.SLA_COMPLIANCE,
                route("Which vendors missed the SLA they signed?"));
    }

    @Test
    @DisplayName("a money question about penalties goes to the billing view, not to OTA")
    void penaltyQuestion() {
        // Penalties live on billing and are dated by cycle, so they cannot be answered
        // from the trip-shaped compliance view.
        assertEquals(Tool.VENDOR_PENALTIES, route("Which vendor has more penalty?"));
        assertEquals(Tool.VENDOR_PENALTIES, route("What is our penalty exposure?"));
    }

    @Test
    @DisplayName("a recurring-pattern question is not the same as a one-window question")
    void repeatQuestion() {
        assertEquals(Tool.REPEAT_OFFENDERS,
                route("Is any vendor consistently missing the target every week?"));
    }

    @Test
    @DisplayName("team language routes to shift readiness, even though it mentions lateness")
    void teamQuestion() {
        assertEquals(Tool.SHIFT_READINESS,
                route("How many of my team were late or did not show up for the morning shift?"));
    }

    @Test
    @DisplayName("a named metric routes to that metric, not to OTA")
    void costQuestion() {
        QueryPlanner.Plan p = planner.keywordPlan("What is driving our cost per trip up?");
        assertEquals(Tool.METRIC_WITH_CONTEXT, p.tool());
        assertEquals("cost_per_trip", p.metricId());
    }

    @Test
    @DisplayName("an on-time question routes to the OTA decomposition")
    void otaQuestion() {
        assertEquals(Tool.OTA_ROOT_CAUSE, route("Why is OTA down this month?"));
    }

    @Test
    @DisplayName("an open 'what is wrong' question scans every metric")
    void generalQuestion() {
        assertEquals(Tool.DEGRADING_METRICS, route("What should I be worried about?"));
    }
}
