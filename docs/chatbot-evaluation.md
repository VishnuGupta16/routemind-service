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

## Answer source

Four of five answers are now written by the model; the fifth falls back to the template
cleanly. `answerSource` on every response says which, so a demo never has to guess.

The LLM writes **only the one-line direct answer**. An earlier design handed it the whole
analysis to re-emit and it reasoned past 8,700 tokens without finishing — so every answer
fell back to the template, slowly, and what it did produce was padded with hypotheticals
("if the morning band were 88.0%…") and arithmetic of its own. Narrowing the job to one
sentence costs ~80 tokens and completes in under a second, and the deterministic sections
keep their computed references untouched.

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

5. **Filters in the question were ignored.** "How is OTA on the night shift?" returned the
   all-trips figure (97.1% over 205,160) instead of the night-shift one (99.4% over
   27,023) — a wrong answer with correct arithmetic behind it. Filters are now extracted
   deterministically and applied by `SlicedMetricService`.

6. **Penalties reported as 0.0%.** Penalty lines live on `billing`, dated by *billing
   cycle*, not trip date — so a trip-date filter found 20 rows. The real answer: one vendor
   carries ₹14.66M, 94.6% of every penalty raised.

7. **Healthy metrics alerted as degrading.** "EV share sliding to 10.9% (target 9.0%) — a
   trend to get ahead of before it breaches" fired while the metric sat 21% clear of its
   floor. Two causes: the alert gate looked only at trend shape, never at proximity to
   target; and the at-risk margin was a flat 2.0 units, which is 2% of an OTA target and
   22% of an EV floor. Both fixed, both pinned by tests.

## Running it

```bash
./gradlew bootRun                  # service on :8080, Postgres loaded
python3 eval_personas.py
```
