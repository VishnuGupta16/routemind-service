# Persona 1 — Transport Manager (operational)

> *"Needs fast, actionable signals, not reports."* — problem statement

## Who they are

Owns day-to-day operations: vendor coordination, escalations, shift planning, delay
management. Judged on whether today ran smoothly. Lives in the gap between "something is
going wrong" and "someone told me". Their enemy is **latency of awareness**.

## Jobs to be done

1. Know *right now* which trips/vendors are failing — without opening a dashboard.
2. Know *who* to call (which vendor, which route) rather than "OTA is down".
3. Catch a problem while it is still fixable, not in the Monday report.
4. Escalate with evidence a vendor cannot argue with.

## Metrics owned

| Metric | Why they care | Reference points attached |
|---|---|---|
| `ota` | the core promise — did people get there on time | SLA 95% · prior period · peer vendors |
| `no_show_rate` | seats paid for and wasted; roster accuracy | target 3% · prior period |
| `safety_alerts_per_1k` | incidents needing response today | target 50 · prior period · by event type |

Configured in `routemind.personas.TRANSPORT_MANAGER`.

## What RouteMind delivers

- **Proactive alerts, unprompted.** `ProactiveScanner` runs on cron, evaluates their three
  metrics, and raises only *new* findings (`cooldown-days` suppresses repeats).
- **Attribution, not just a number.** Every finding names the responsible vendor and its
  share — "13.3% of all late trips" — which is what makes an escalation stick.
- **Both views of "worst".** Worst *rate* (Pooja Mikhailov Travel, 93.2% over 17,191 trips)
  and biggest *volume* contributor (Sanjay Mikhailov Travel, 13.3% of lateness). These are
  different vendors; acting on only one is a common mistake.
- **Pre-trip risk briefing.** `/api/live/risk` flags tomorrow's likely failures from
  history × traffic profile — before the shift starts.
- **In-flight alerts** (when the GPS feed is on): fused live+historical prediction fires
  mid-journey with a recommended action and the affected headcount.
- **Recommended action on every finding**, e.g. *"Recommend an SLA review with X, which
  owns 13.3% of late trips."*

## Endpoints

```
GET  /api/insights/TRANSPORT_MANAGER?from&to[&businessUnit]   ranked findings + narrative
GET  /api/live/risk?day                                        tomorrow's risky trips
GET  /api/live/alerts                                          in-flight alerts raised
GET  /api/live/stream                                          SSE push feed
POST /api/scan?asOf                                            force a proactive scan
```

## Example output

> **[HIGH] On-time arrival** · BELOW_TARGET
> On-time arrival is 93.2% across 17,191 trips, against a 95.0% target (down from 95.2%
> last period). Sanjay Mikhailov Travel accounts for 13.3% of the impact.
> Below target — action needed.
> **Recommend an SLA review with Sanjay Mikhailov Travel, which owns 13.3% of late trips.**

## 90-second demo

1. Open dashboard → persona = Transport manager → **Insights**. Point at the alert: value,
   target, trend, culprit, action — all in one card.
2. **Predicted risk** tab → "these trips will be late tomorrow, before anyone has waited".
3. `POST /api/scan` → show it fires on its own, and re-running is suppressed by cooldown.

## Coverage vs the brief

| Requirement | Status |
|---|---|
| Runs on the sample dataset | ✅ 608,793 trips |
| Senses, reasons, acts | ✅ scan → rank → narrate → recommended action |
| Serves a named persona | ✅ this one |
| Every metric carries context | ✅ SLA + prior + attribution enforced by the type |
| Triggers on its own | ✅ cron + cooldown |
| Handles messy data | ✅ capability flags; nulls excluded from denominators |

## Gaps

- Actions are **recommended, not executed** — no write-back into a dispatch system.
- In-flight alerting needs the Kafka GPS feed; without it this persona is pre-trip only.
- No push channel yet (email/Slack) — alerts live in the API/SSE feed.
