# Persona 2 — Transport & Facilities Head (strategic)

> *"Needs a coherent cost/safety/experience story without assembling it manually."*

## Who they are

Owns budget, SLA accountability, vendor strategy and leadership reporting across sites and
business units. Does not want to *operate*; wants to *decide* — and to walk into a review
with a defensible narrative. Their enemy is **assembly time**: the hours spent stitching
five reports into one story.

## Jobs to be done

1. Answer "how is mobility doing?" across cost, safety, experience and sustainability — in
   one artefact, without building it.
2. Hold vendors to account with evidence, at contract-renewal scale.
3. Spot a trend before it becomes a budget problem.
4. Forward something to the VP **without rework**.

## Metrics owned

| Metric | Why they care | Reference points |
|---|---|---|
| `cost_per_trip` | the budget line | target ₹1,000 · prior period · vendor share of spend |
| `ota` | the SLA they signed | 95% · prior · peer vendors |
| `safety_alerts_per_1k` | duty of care, board-level risk | target 50 · prior · by event type |
| `experience` | attrition and satisfaction | target 4.5/5 · prior · worst vendors |
| `seat_utilisation` | where money leaks | target 70% · prior · empty-seat leaders |
| `ev_share` | ESG commitments | target 25% · prior · vendors to convert |

Configured in `routemind.personas.FACILITIES_HEAD` — all six.

## What RouteMind delivers

- **A forwardable HTML briefing** (`/api/report/FACILITIES_HEAD`) — headline findings with
  severity, every metric with its reference point, attribution tables, recommended actions,
  and a footer stating that figures are computed and only wording is generated. No
  placeholders, no raw IDs, no "TBD".
- **The whole estate in one view** — 5 business units, 17 offices, 23 vendors; scope with
  `businessUnit` or leave blank for the group picture.
- **Ranked, not exhaustive.** `InsightRanker` scores materiality (gap × confidence ×
  severity) so the briefing opens with the two things that matter, not 40 tiles.
- **Trend as a first-class citizen** — every metric carries prior-period delta, so "OTA is
  96.4%" becomes "96.4%, down from 97.2% in May".
- **Vendor scorecard logic** — both rate-based and volume-based attribution, which is the
  fair basis for a contract conversation.
- **Projection** — `ErrorBudgetService` says whether the month is on pace to breach, and
  names the date. Strategic personas care about *will we*, not *did we*.

## Endpoints

```
GET /api/report/FACILITIES_HEAD?from&to[&businessUnit]     the briefing (HTML)
GET /api/insights/FACILITIES_HEAD?from&to                  ranked findings as JSON
GET /api/metrics?from&to                                   all six with context
```

## Example output (report extract)

> **[HIGH] Cost per trip** · BELOW_TARGET
> Cost per trip is ₹1,180 across 620,942 billed trips, against a ₹1,000 target (up from
> ₹1,140 last period). Rohan Mikhailov Travel accounts for 21.4% of spend.
> Below target — action needed.
> **Recommend renegotiating the slab with Rohan Mikhailov Travel, which carries 21.4% of spend.**
>
> | Metric | Value | Target | Previous | Status |
> |---|---|---|---|---|
> | On-time arrival | 96.4% | 95.0 | 97.2 | AT_RISK |
> | Employee experience | 4.42/5 | 4.5 | 4.51 | BREACH |
> | Electric vehicle share | 11.8% | 25.0 | 10.9 | BREACH |

*(illustrative shape — exact values come from the query)*

## 90-second demo

1. Dashboard → persona = Facilities head → **Report ↗**. It opens a clean HTML briefing.
2. Say the line: *"this is forwardable as-is — that is the requirement."*
3. Switch business unit → same briefing, re-scoped, no code change (multi-tenancy).
4. Show `/api/config` → targets are config; a new tenant changes YAML, not Java.

## Coverage vs the brief

| Requirement | Status |
|---|---|
| Leadership-ready, shareable without rework | ✅ the report is the artefact |
| Every metric carries context | ✅ all six, enforced |
| Combines two+ solution forms | ✅ automated reporting + narrative + insight detection |
| Multi-tenancy (bonus) | ✅ 5 BUs × 17 offices, one config per tenant |
| Cost at scale | ✅ one LLM call per ranked finding, cached |

## Gaps

- Report is HTML only — no PDF/email delivery yet (browser print is the workaround).
- No cross-BU benchmarking view ("how does Austin compare to Seattle") — the data supports
  it; the peer-comparison reference point is not yet wired into the report.
- Sustainability is EV share only; no CO₂ estimate.
