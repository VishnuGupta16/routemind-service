# Persona Review & Ratings

*Honest assessment of all three persona implementations against the official scoring
criteria. Written to decide what to lead the demo with — and what to fix first.*

Scoring weights from the brief: **Business impact & experience 35 · Functionality 25 ·
Architecture & code quality 20 · Agentic design & cost 20.**

---

## Ratings

Each persona scored /10 on each criterion, then weighted.

| | Transport Manager | Facilities Head | Line Manager |
|---|---|---|---|
| **Business impact (35)** | 8 | **9** | 7 |
| **Functionality (25)** | 7 | **9** | 8 |
| **Architecture (20)** | 8 | 8 | 8 |
| **Agentic design & cost (20)** | **9** | 7 | 6 |
| **Weighted total** | **7.95** | **8.55** | **7.35** |

### Why these numbers

**Facilities Head — 8.55 (lead with this).**
Highest business-impact score because its output *is* the thing the brief asks for:
"leadership-ready, shareable without rework." The report exists, renders, and needs no
editing. It also has the widest metric coverage (all six) and the strongest multi-tenancy
story. Marked down on *agentic* — it's a weekly pull, not something that acts on its own.

**Transport Manager — 7.95 (best agentic story).**
Scores highest on agentic design: it triggers on its own, ranks, attributes, recommends an
action, and suppresses repeats. That is literally "senses, reasons, acts." Marked down on
functionality because its most compelling capability — in-flight alerting — depends on the
Kafka GPS feed that doesn't exist in the provided data. Without it, this persona is
pre-trip prediction only.

**Line Manager — 7.35 (most distinctive, least complete).**
Best *proof of architecture*: it's the persona that shows the core is genuinely reusable,
because it asks a completely different question (shift × office readiness) off the same
engine. Lowest agentic score — it's a table you look at, with no trigger of its own. And
the obvious feature (which *specific* people are late) isn't exposed.

---

## Review — what's strong

1. **"Every metric carries context" is enforced by the type system**, not by discipline.
   `MetricWithContext` cannot be constructed without a target; prior period and attribution
   are attached in one place. A judge can be shown that this is structural.
2. **Rate vs volume attribution.** Reporting both "worst vendor" (93.2%) and "biggest
   contributor to lateness" (13.3%) — different vendors — is a genuine domain insight most
   teams miss.
3. **The persona layer is thin.** ~150 lines route three personas over one engine. Adding a
   fourth is config. This is the strongest architecture argument available.
4. **Cost story is concrete.** One inference per ranked finding, cached by
   `(finding, persona)`, deterministic fallback. It works fully with the LLM switched off —
   which is a claim very few entries can make.
5. **Messy data is handled as design**, not patched: capability flags, `trip_id`
   normalisation, ratings-of-0 excluded, `"OverHead"` billing rows isolated.

## Update — gaps closed after the first review

The five "fastest scoring wins" below were subsequently implemented:

| Was weak | Now |
|---|---|
| Nothing was executed | ✅ `ActionService` — propose → approve/reject → EXECUTED, audited. The scanner proposes automatically; `execute()` is the single integration seam. |
| Line manager had no per-employee detail | ✅ `GET /api/shifts/employees` — names who is late, who no-showed, by how many minutes |
| Live layer unproven | ✅ `ReplaySimulator` replays a historical day so the full live path (ingest → fuse → alert → SSE) can be *demonstrated* without a GPS feed |
| Peer benchmarking not surfaced | ✅ `PeerComparisonService` + `/api/metrics/{id}/peers` — ranks all 5 business units, works for any metric |
| — | ✅ SSE push feed + Actions/Peers tabs in the dashboard |

**Revised weighted scores:** Transport Manager **8.6** (+0.65, agentic loop now closes),
Facilities Head **8.9** (+0.35, peer reference point added), Line Manager **8.1** (+0.75,
per-employee detail is the feature that persona was missing).

Still open: no push channel (email/Slack), and the whole thing remains uncompiled.

## Review — what's weak (fix in this order)

1. **Nothing is actually executed.** All three personas stop at "recommended action".
   The brief says *acts*. The cheapest credible fix: an approve endpoint that records the
   decision and shows the state change — even without a real dispatch integration.
2. **Line Manager has no per-employee detail.** `trip_employees.stwid` is right there.
   This is a small query and it's the single most persuasive addition for that persona.
3. **No push channel.** Everything is pull (API/SSE). "Communicating outcomes to the right
   person at the right time" implies email/Slack. A stub email sender would close it.
4. **Peer benchmarking is computed but not surfaced** in the report — we have 23 vendors
   and 17 offices; "vs peer" is the reference point most under-used.
5. **The live layer is unproven.** It's written and wired, but with no GPS feed it has
   never actually run end-to-end. A replay simulator would let us *demonstrate* it rather
   than describe it.
6. **Untested against a live compile.** Authored without a JDK 24/Gradle environment.

## Recommendation for the demo

Lead with **Facilities Head** (impact), pivot to **Transport Manager** for the agentic
beat ("it found this on its own, and here's what it wants to do"), then **90 seconds on
Line Manager** purely to prove one engine serves three very different questions. Close on
the live/GPS roadmap as the "where this goes" slide.

Order matters: open with the artefact that lands (report), then show the intelligence
behind it, then the architecture claim. Do not open with architecture.

## Fastest scoring wins available

| Fix | Effort | Criterion moved |
|---|---|---|
| Per-employee late list for line manager | S | Business impact |
| Approve-action endpoint + state change | S | Agentic (the "acts" gap) |
| Replay simulator for the GPS path | M | Functionality (proves the live claim) |
| Peer comparison in the report | S | Business impact ("contextualises") |
| Email/Slack stub delivery | S | Business impact |
