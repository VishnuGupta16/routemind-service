# routemind-service

**Spring Boot 4 · Java 24 · Gradle · virtual threads** — implementation of the MoveInSync
**Agentic Intelligence & Reporting Layer**. Senses → reasons → acts, over the normalised
Postgres dataset, for **all three personas**.

📊 **[Demo deck](https://claude.ai/code/artifact/7a18bfaf-7baa-4c62-87ff-8a9d2a63b52a)** —
how degrading metrics are detected, how OTA is decomposed into root causes, what the LLM
is allowed to do, and the architecture.

📐 **[Design deck — HLD & LLD](https://claude.ai/code/artifact/05432731-1ff8-4e37-9653-c40cc29672be)** ([source](docs/routemind-design-deck.html)) —
mermaid diagrams of the system boundary, the detection gate, the RCA decomposition, the
chat sequence and the alert lifecycle.

📄 **[Architecture notes](docs/architecture.md)** · 🧪 **[Chatbot evaluation](docs/chatbot-evaluation.md)**

### The chatbot answers only from this data

The model chooses *which* analysis to run; it never produces the numbers.

1. **An existing service, always first** — seven tools, each already applying the SLA
   policy, product exclusions and attribution rules.
2. **Bounded SQL only if none fits** — read-only, allow-listed tables, period and business
   unit injected as bound parameters so the model cannot widen its own slice.
3. **At most two attempts**, then answer from what the services returned.

No internet, no general knowledge, no industry averages. If the data does not cover the
question the answer is *"Still working on it — our data doesn't cover that yet."*
Every model call is traced at `GET /api/chat/trace`.

### Alerts land in the app

`IN_APP` is the default channel, so the whole sense → reason → notify loop runs with no
mail server. `POST /api/alerts/trigger/{code}` is the UI button and takes the same path as
the 07:00 cron.

## Run

```bash
psql "postgresql://routemind:pass@localhost:5434/routemind" -f verify.sql   # check the load
./gradlew bootRun
open http://localhost:8080/                                                 # dashboard
```

## Modules

Each module has one job and a clean seam. Nothing below the line knows about personas;
nothing above it knows about SQL.

```
com.routemind
├── metrics/spi/     MetricDefinition  ← THE plug point. New metric = one class.
│   └── defs/        ota · no_show_rate · cost_per_trip · experience
│                    safety_alerts_per_1k · seat_utilisation · ev_share
├── metrics/         MetricService (value + target + prior + attribution + projection)
├── predict/         ErrorBudgetService — period-end breach projection (arithmetic, no ML)
├── rules/           RuleSetProperties (declarative) · RuleEvaluator · InsightRanker · Finding
├── narrative/       NarrativeGenerator (iface) · Template (always works) · Sarvam (LLM)
├── persona/         Persona (3) · PersonaRouter · ShiftReadinessService
├── report/          ReportComposer — forwardable HTML briefing
├── schedule/        ProactiveScanner — "it triggers on its own", with cooldown
├── live/            TripPositionProvider · TrafficProvider · LiveRiskService  ← future GPS
├── onboarding/      FieldIdentifier (3 tiers) · OnboardingService · SourceProfile
└── api/             MetricController · InsightController · LiveController
```

### Adding a metric (the modularity test)

```java
@Component
public class MyMetric implements MetricDefinition {
    public String id() { return "my_metric"; }
    public MetricPoint compute(MetricQuery q, NamedParameterJdbcTemplate jdbc) { ... }
}
```
That's it. It is now benchmarked, evaluated by rules, ranked, narrated, routed to
personas, shown on the dashboard, and included in reports. No other file changes.

## The three personas

| Persona | Lens | Cadence | Gets |
|---|---|---|---|
| **Transport manager** | ops — what's wrong now, who to call | realtime | alerts: ota, no-shows, safety |
| **Transport & facilities head** | strategy — cost/safety/experience story | weekly | forwardable HTML report, all 6 metrics |
| **Line manager** | shift readiness — who made it, who was late | per shift | `/api/shifts` — boarded vs expected, per shift/office |

Personas are **config** (`routemind.personas.*`), not code. Change the metric list, or add
a persona, without touching Java.

## Endpoints

```
GET  /api/health/data                      row counts, business units, date range
GET  /api/metrics?from&to[&businessUnit]    all metrics, each WITH context
GET  /api/metrics/{id}?from&to              one metric
GET  /api/metrics/{id}/peers?from&to        PEER reference point — every BU ranked
GET  /api/personas                          the three personas + what they own
GET  /api/insights/{persona}?from&to        ranked findings + narrative for that persona
GET  /api/insights?from&to                  all three personas at once
GET  /api/shifts?day                        line-manager shift readiness
GET  /api/shifts/employees?day[&shift]      WHICH employees are late / did not board
GET  /api/report/{persona}?from&to          leadership-ready HTML briefing
POST /api/scan[?asOf]                       run the proactive scan now

POST /api/actions/propose?persona&from&to   turn findings into proposed actions
GET  /api/actions[?state]                   the action queue
POST /api/actions/{id}/approve              approve -> recorded as EXECUTED
POST /api/actions/{id}/reject?reason        reject with a reason

GET  /api/live/risk?day                     trips predicted to fail, before they do
GET  /api/live/predict/{tripId}             fused live+prior+traffic prediction
GET  /api/live/alerts                       in-flight alerts raised
GET  /api/live/stream                       SSE push feed
POST /api/live/replay/start?day&speed       replay a historical day as if live
GET  /api/live/status                       mode, fusion, alerting, replay state

POST /api/onboarding/propose                columns in → proposed mapping + capabilities
GET  /api/config                            active rules, targets, narrative engine

GET  /api/sla/policies                      configured SLAs (most specific first)
POST /api/sla/policies                      add an SLA during vendor onboarding
PUT  /api/sla/policies/{id}                 amend it
DEL  /api/sla/policies/{id}                 deactivate it
GET  /api/sla/resolve?vendor&productType..  which SLA applies to a combination
GET  /api/sla/compliance?from&to[&groupBy]  vendor scorecard vs each vendor's OWN SLA

POST /api/schema/report                     ingest a validate.py report
GET  /api/schema/changes[?pendingOnly]      detected schema changes + proposals
POST /api/schema/changes/{id}/adopt         add column (nullable, no backfill)
POST /api/schema/changes/{id}/ignore        record & stop warning
POST /api/schema/changes/{id}/reject        fail future drops containing it
GET  /api/schema/decisions                  overlay consumed by etl/validate.py
```

## Schema evolution, decided in the UI

When a monthly drop contains a column we've never seen, the ETL gate blocks the load and
pushes it here. The **Schema** tab shows it as PENDING with a profile (type, % populated,
distinct count, samples) and a proposal — written by the **LLM** where configured, or a
deterministic heuristic otherwise. One click adopts, ignores or rejects it.

**Adoption is backward compatible by construction:** the column is added `NULLABLE` with
no default and no backfill, so historical rows read NULL meaning *"not collected then"* —
and because no query uses `SELECT *`, no existing metric can change value. Every adopted
column records `availableFrom`; anything computing over it must scope to that date or it
will average across a period where the column didn't exist.

This is the second place the LLM is used (the first is narrative). Both are once-per-event,
both are judgement rather than arithmetic, and both fall back to deterministic logic.

## The act loop (sense → reason → **act**)

The scanner doesn't just report; it puts a concrete proposal on someone's desk:

```
ProactiveScanner (cron)
   └─ finding: "OTA 93.2% vs 95% SLA; Vendor A owns 13.3% of lateness"
        └─ ActionService.proposeFrom(finding)
             └─ PROPOSED  "Recommend an SLA review with Vendor A"
                  ├─ POST /api/actions/{id}/approve  → APPROVED → EXECUTED (audited)
                  └─ POST /api/actions/{id}/reject   → REJECTED (with reason)
```

`ActionService.execute()` is the single seam where a real dispatch / vendor-management
integration would land. Nothing external is touched without an explicit approval.

## Design guarantees

- **Every metric carries context.** `MetricWithContext` cannot be built without a target;
  prior period and attribution are attached automatically.
- **No LLM produces a number.** All maths is SQL. The model only rephrases a `Finding`
  whose figures are already fixed — and the deterministic template is the fallback, so a
  missing key or a failed call degrades wording, never correctness.
- **Cost at scale.** One inference per *ranked finding*, not per metric or row, and cached
  by `(finding, persona)`. Ten people opening the same report costs one call. With the LLM
  off, the product still fully works.
- **It triggers on its own.** `ProactiveScanner` runs on cron, evaluates all three personas,
  and suppresses repeats via `cooldown-days`.
- **Messy data is designed for.** Onboarding computes capability flags; a metric whose
  inputs are missing is disabled rather than returning a wrong number.

## Future: live GPS + traffic

`LiveRiskService` predicts which trips will run late. Today it learns from history
(vendor × office × shift lateness) scaled by a traffic profile derived from
`delay_reason = 'TRAFFIC'` — real signal, working now, no GPS required.

When live feeds arrive, implement one interface each and mark it `@Primary`:

```java
@Primary @Component
class GpsFeed implements TripPositionProvider {          // vehicle stream / Kafka
    public Optional<TripPosition> positionOf(long tripId) { ... }
    public boolean live() { return true; }
}

@Primary @Component
class MapsTraffic implements TrafficProvider { ... }     // Google / Mapbox / TomTom
```

`LiveRiskService` then switches automatically from *"this vendor is late 30% of the time
on this shift"* to *"this cab is 9.2 km out at 22 km/h — it will arrive 18 minutes late,
9 employees affected, dispatch a backup now"*. **The API, the UI, the alerting and the
persona routing do not change** — only the `basis` field and the accuracy do.
`/api/live/status` reports which mode is active.

## Runtime & concurrency

- **Java 24 + virtual threads** (`spring.threads.virtual.enabled=true`). Every request is
  blocking I/O — Postgres aggregations plus at most one outbound LLM call — which is the
  ideal virtual-thread workload. JDK 24 (JEP 491) removed the `synchronized` pinning that
  previously made virtual threads counterproductive under JDBC drivers.
- With virtual threads the **connection pool, not the thread pool, is the ceiling** — so
  Hikari is sized to the database (20), not to the request count.
- **Spring Boot 4** notes: the `io.spring.dependency-management` plugin is replaced by
  Gradle's native BOM platform, and Boot 4 ships **Jackson 3** (`tools.jackson`) — the LLM
  client deliberately parses via Spring's message converters rather than importing Jackson
  directly, so it is unaffected by that move.

## Notes before first run

1. **No Gradle wrapper is committed** — run `gradle wrapper` once, or let your IDE import.
   (Gradle 9.3+ is needed for a Java 24 toolchain.)
2. This was authored against the verified dataset but **could not be compiled here**
   (that environment has JDK 11 and no Gradle). Expect at most a dependency-version nudge.
3. Requires **JDK 24** and the Postgres load from `../etl`.
4. LLM narrative is **off by default**. Turn it on with
   `routemind.narrative.sarvam.enabled=true` and an `api-key`.

## Endpoints added for question-shaped answers

The metric board answers "how are we doing". These answer the questions it could not.

| Endpoint | Question |
|---|---|
| `GET /api/slice/ota?shiftBand=NIGHT` | "How is OTA on the night shift?" — one metric restricted to a slice the question named. At least one filter is required; without one, use `/api/metrics/ota`. |
| `GET /api/slice/repeat-offenders` | "Who is *consistently* bad?" — vendors below target in a third or more of the weeks measured. The prior-period comparison only ever sees one window against one, so it cannot see a vendor that has simply always been bad. |
| `GET /api/slice/penalties` | "Which vendor carries the most penalties?" — penalties live on `billing`, dated by **billing cycle** rather than trip date, so a trip-date filter finds almost nothing. |
| `GET /api/alerts` · `/summary` | The in-app alert inbox and its unread badge. |
| `POST /api/alerts/trigger/{code}` | Run an alert now and deliver it to the inbox. |
| `GET /api/chat/trace` | Every LLM call: purpose, latency, tokens, outcome. |
