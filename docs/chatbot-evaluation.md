# Chatbot evaluation — per persona

The QA chatbot is evaluated on four machine-checkable criteria, run against the live
service over the June 2026 window (the month OTA actually fell).

Harness: `eval_personas.py` — posts each question to `/api/chat` and inspects the response.

## Criteria

| Criterion | What it checks | Why it matters |
|---|---|---|
| **Persona routing** | The answer is framed for the persona the question is written from | The same numbers mean different things to a facilities head and a line manager |
| **Grounded** | Every number in the prose also appears in the structured `facts` | This is the anti-hallucination guarantee — a figure with no fact behind it is invented |
| **Actionable** | The answer names a responsible party *and* a next step | "OTA is down" is not an action; "raise Pooja Mikhailov Travel at contract review" is |
| **Evidence** | Facts are returned, each with a reference (target, prior period or SLA) | A number without its reference point cannot be argued from |

## Results

| Question | Persona | Tool chosen | Result |
|---|---|---|---|
| Why is OTA down this month and which vendor should I call first? | TRANSPORT_MANAGER | `OTA_ROOT_CAUSE` | ✅ 4/4 |
| Which vendors missed the SLA they signed, and what is our penalty exposure? | FACILITIES_HEAD | `SLA_COMPLIANCE` | ✅ 4/4 |
| How many of my team were late or did not show up for the morning shift? | LINE_MANAGER | `SHIFT_READINESS` | ✅ 4/4 |
| What is driving our cost per trip up? | FACILITIES_HEAD | `METRIC_WITH_CONTEXT` | ✅ 4/4 |
| Which office has the worst on-time arrival right now? | TRANSPORT_MANAGER | `METRIC_WITH_CONTEXT` | ✅ 4/4 |

```
persona routing                    : 5/5
grounded (no hallucinated numbers) : 5/5
actionable (party + next step)     : 5/5
evidence with references           : 5/5
```

## What the evaluation caught

The first run scored **3/5 persona, 4/5 actionable, 3/5 evidence**. Four defects, all fixed:

1. **Contract questions were answered operationally.** "Which vendors missed the SLA they
   signed" scored higher on the transport keyword list (it contains *"which vendor"* and
   *"breach"*) than on the facilities list. Fixed with decisive-cue lists that outrank
   generic matches, plus tests for each phrasing.

2. **Every question ran the same OTA decomposition.** Asking about cost returned
   OTA-by-direction. Fixed by introducing `QueryPlanner`, which picks one tool per question.

3. **Answers contradicted themselves.** A reply would state *"nothing is degrading"* and
   then explain a 2-point fall, because the "what's wrong" section assumed a full scan had
   run. It now says the answer is scoped to what was asked.

4. **Selected tools returned no data.** `SHIFT_READINESS` and `METRIC_WITH_CONTEXT` were
   chosen but never executed — 0 facts returned. Both now call their service.

## Running it

```bash
./gradlew bootRun                  # service on :8080, Postgres loaded
python3 eval_personas.py
```
