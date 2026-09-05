# Persona 3 — Team / Line Manager (shift-based ops)

> *"Needs shift-level visibility into who made it, who was late, and how delays ripple
> into floor/ops readiness."*

## Who they are

Runs a floor or a team on a specific shift. Does not care about vendor SLAs or the monthly
cost line — cares whether **their people are at their desks**. Their enemy is **surprise**:
finding out at shift start that six of forty are stuck in traffic.

This is the persona most often ignored, and the one with the most distinct data need — it
is the reason the system has a genuinely different query rather than a filtered dashboard.

## Jobs to be done

1. At shift start: who boarded, who didn't, who is still en route.
2. Understand the *ripple*: 6 late arrivals on a 40-person shift = 15% floor shortfall.
3. Get told before the shift, not during it.
4. Escalate the specific pickup, not the whole vendor relationship.

## Metrics owned

| Metric | Why they care | Reference |
|---|---|---|
| `ota` | late trips = late people | SLA 95% · prior period |
| `no_show_rate` | seats held for people who never came | target 3% · prior |

Plus a **shift-level view** that isn't a metric at all — see below.

## What RouteMind delivers

The other two personas are served by period aggregates. This one needed its own query:
**`ShiftReadinessService`** joins `trips` and `trip_employees` at `(shift × office)` grain
for a single day and returns:

| Field | Meaning |
|---|---|
| `trips` / `lateTrips` / `otaPct` | punctuality for that specific shift |
| `employeesExpected` / `employeesBoarded` | headcount reality |
| `noShows` | booked and not travelled |
| `readinessPct` | **boarded ÷ expected** — the floor-readiness number |
| `note` | plain-English consequence |

The `note` is the persona-appropriate translation:

- `"Floor short — 18% of expected staff did not board."`
- `"4 late trips on this shift — expect a staggered start."`
- `"3 no-shows; seats were paid for but unused."`
- `"On track."`

Ordered worst-OTA-first, so the shift needing attention is row one.

**With the live GPS feed on**, this persona gains the highest-value signal in the system:
an alert *before* the shift starts naming how many of their people will be late and by how
long — while there is still time to stagger the floor or reassign a cab.

## Endpoints

```
GET /api/shifts?day=2026-07-15[&businessUnit]        shift readiness table
GET /api/insights/LINE_MANAGER?from&to               ranked findings, their two metrics
GET /api/live/alerts                                 in-flight, with employeesAffected
```

## Example output

| Shift | Office | Trips | Late | OTA | Boarded | Readiness | Note |
|---|---|---|---|---|---|---|---|
| 09:00 | Denver Office | 42 | 9 | 78.6% | 118/140 | 84.3% | Floor short — 16% of expected staff did not board. |
| 11:00 | Cedar Ridge | 30 | 2 | 93.3% | 96/98 | 98.0% | 2 no-shows; seats were paid for but unused. |
| 21:30 | Clearwater | 18 | 0 | 100% | 54/54 | 100% | On track. |

## 90-second demo

1. Dashboard → **Shift readiness** tab, pick a date.
2. Point at the worst row: *"84% readiness — this manager is short 22 people and nobody
   told them."*
3. Contrast with the facilities-head report: **same engine, completely different question.**
   That's the "one core, three personas" argument in one screen.

## Coverage vs the brief

| Requirement | Status |
|---|---|
| Serves a named persona | ✅ and with a genuinely distinct lens, not a filter |
| Shift-level visibility | ✅ `(shift × office)` grain, boarded vs expected |
| Delay ripple into floor readiness | ✅ `readinessPct` + plain-English note |
| Every metric carries context | ✅ for their two metrics |

## Gaps

- **No per-employee list** — we report counts, not "these 6 people are late". The data
  supports it (`trip_employees.stwid`), it just isn't exposed; it's the most obvious next
  addition for this persona.
- Shift view is **one day at a time**; no "next shift" forward look without the GPS feed.
- No direct notification channel — a line manager would want this pushed, not polled.
