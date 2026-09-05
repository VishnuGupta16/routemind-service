# Architecture

One Spring Boot service over Postgres, with the LLM strictly at the edge. Every layer
degrades to the one beneath it: no single failure takes the product down, it only makes
the wording less fluent.

```
Angular 18 (dev :4200, proxy → :8080)
        │
        ▼
REST API  /metrics  /diagnose  /slice  /insights  /chat  /alerts  /sla  /live  /admin  /schema
        │
        ▼
Domain    MetricDegradationService · OtaRootCauseService · QueryPlanner · SafeSqlExecutor
          SlicedMetricService · PersonaRouter · SlaPolicyService · ProactiveScanner
        │
        ▼
Metric SPI (9 plugins)   ota · cost_per_trip · no_show_rate · experience · safety_alerts_per_1k
                         seat_utilisation · ev_share · worst_product_ota · penalty_exposure
        │
        ▼
Postgres  trips 615K · trip_employees 1.6M · billing 620K · feedback 513K · alerts 51K
          (ETL is a one-time load-and-clean, not part of the request path)
```

## The chatbot's decision path

The model chooses *which* analysis to run; it never produces the numbers.

```
question
   │
   ├─ PersonaClassifier   who is asking          → framing
   ├─ QueryPlanner        which tool             → one of 7
   └─ QueryPlanner.Filters  which slice          → deterministic, never model-authored
                     │
                     ▼
   ┌─────────────────────────────────────────────────────────┐
   │ 1. AN EXISTING SERVICE, ALWAYS FIRST                     │
   │    OTA_ROOT_CAUSE · DEGRADING_METRICS · SLA_COMPLIANCE   │
   │    METRIC_WITH_CONTEXT · SHIFT_READINESS                 │
   │    REPEAT_OFFENDERS · VENDOR_PENALTIES                   │
   ├─────────────────────────────────────────────────────────┤
   │ 2. BOUNDED SQL, only if no service fits                  │
   │    read-only · allow-listed tables · period and business │
   │    unit injected by US as bound parameters               │
   ├─────────────────────────────────────────────────────────┤
   │ 3. AT MOST TWO ATTEMPTS, then answer from what we have   │
   └─────────────────────────────────────────────────────────┘
                     │
                     ▼
   facts + benchmark → model formats the prose → answer
```

### Why API-first

Every service already applies the SLA policy, the product exclusions (`SPOT_2.0` rentals
carry no on-time commitment) and the attribution rules. A hand-written query skips all of
that and produces a number that disagrees with the dashboard — worse than not answering.

### Grounding rules

* Answers come only from this operation's own data. Never general knowledge, never
  industry averages.
* If the data does not cover the question: **"Still working on it — our data doesn't
  cover that yet."** Never an estimate.
* Any verdict against a target names the reference it was judged against — a number
  without its comparison set is not an answer.

## Tools, and why each exists

| Tool | Answers | Why it is not just a filter on another endpoint |
|---|---|---|
| `OTA_ROOT_CAUSE` | "Why did OTA move?" | Shift-share decomposition across 5 dimensions |
| `DEGRADING_METRICS` | "What is going wrong?" | Trend shape across every metric |
| `SLA_COMPLIANCE` | "Who missed their contract?" | Judged per-vendor against the SLA each signed |
| `METRIC_WITH_CONTEXT` | "How is X doing?" | One metric with target, prior and contributors |
| `SHIFT_READINESS` | "Who on my team was late?" | Per-employee, per-shift |
| `REPEAT_OFFENDERS` | "Who is *consistently* bad?" | Weekly buckets — the prior-period comparison only ever sees one window against one, so it cannot see a vendor that has always been bad |
| `VENDOR_PENALTIES` | "Who carries the most penalties?" | Penalties live on `billing`, dated by **billing cycle**, not trip date — a July penalty can be raised in August |

## Slices

`SlicedMetricService` answers "how is OTA on the night shift?". The nine registered metrics
deliberately take no shift/direction/product filter — adding optional predicates to all of
them would make each harder to read for a case only OTA needs. The sliced query mirrors
`OtaMetric` exactly (same SLA window, same rental exclusion) so a slice and the headline
can never be computed on different bases. It is deterministic, not model-written.

## Alerting

Alerts are delivered **into the app**, not to an inbox, so the whole sense → reason →
notify loop is demonstrable with no mail server configured.

```
AlertScheduler (ticks every 60s)
   └─ reads alert_schedule — cron, timezone, next_run_at
        └─ ReportService.run(code, asOf, bu, force)
             └─ ReportGenerator (SPI)
                  └─ NotificationChannel: IN_APP → in_app_alert table → UI inbox
                                          EMAIL  → JavaMailSender (optional)
```

The tick is the only fixed timing in the system; everything about *when* comes from the
row, so an admin changes a cadence from the UI rather than by redeploying. The UI's
"trigger now" button calls the same `ReportService.run` the scheduler calls — identical
path, which is what makes the demo honest.

| Alert | Cadence | Persona |
|---|---|---|
| `transport_manager_signals` | weekdays 07:00 IST | Transport manager |
| `weekly_detailed_report` | Mondays 08:00 IST | Facilities head |
| `facilities_head_briefing` | 1st of month 08:00 IST | Facilities head |

## LLM call tracing

`LlmTrace` records every outbound model call — purpose, latency, tokens, outcome —
exposed at `GET /api/chat/trace`. Two reasons it is a table rather than a log line:

* **Cost.** A reasoning model bills its own thinking. `sarvam-105b` spends ~570 completion
  tokens answering *"say OK"*. Budgeting only for the answer length made every reply come
  back `finish_reason: length` with `content: null` — indistinguishable from "no model
  configured", so the product silently ran on templates while reporting the LLM as active.
* **Trust.** The claim "the model never invents a number" is only checkable if you can see
  what it was asked and what came back.

## Extension points

* **A metric is a plugin.** Implement `MetricDefinition`, annotate `@Component`. It is then
  automatically registered, benchmarked, rule-evaluated, narrated and persona-routed.
* **A report is a plugin.** Implement `ReportGenerator` with a `key()` matching
  `alert_definition.generator_key`.
* **A channel is a plugin.** Implement `NotificationChannel`; subscriptions reference a
  channel by `kind()`, never by an address.
* **A tenant is config.** Targets, SLA tolerances, persona→metric routing, alert cadence
  and product exclusions live in YAML or the database. A new customer is a config change.

## Operational notes

* **Virtual threads** are on. The workload is blocking I/O (Postgres + one outbound LLM
  call), so each request gets a cheap virtual thread and the connection pool — not the
  thread pool — is the real limit. JDK 24 (JEP 491) removed the synchronized-block pinning
  that used to make this counterproductive under JDBC.
* **Database port** is `${DB_PORT:5434}`. Docker publishes the container on 5434 when 5432
  is already taken on the host, which it usually is on a dev laptop.
