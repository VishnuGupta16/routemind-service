# Data ingestion pipeline — raw MoveInSync CSVs → Postgres

A pipeline that runs **separately from the RouteMind service**, on its own schedule or by
hand. Three stages, always in this order. Verified end to end on the real May–July 2026
dataset: **3.4M rows**.

```
                      raw monthly drop
                            │
   ┌────────────────────────▼────────────────────────────────────────────┐
   │ STAGE 1  VALIDATE          validate.py  ·  contracts.yml  ·  LLM    │
   │                                                                     │
   │   Deterministic contract check decides PASS / FAIL.                 │
   │   An LLM then EXPLAINS the findings a rule cannot judge and         │
   │   proposes an action. It never changes the verdict.                 │
   └────────────────────────┬────────────────────────────────────────────┘
                            │  FAIL ⇒ stop. Nothing reaches Postgres.
   ┌────────────────────────▼────────────────────────────────────────────┐
   │ STAGE 2  CLEAN             clean.py  ·  rules.yml                   │
   │                                                                     │
   │   Every cleaning rule is declared in rules.yml with a `why:`        │
   │   citing the organisers' data dictionary or a STAGE 1 finding.      │
   │   clean.py is only an interpreter for that file.                    │
   └────────────────────────┬────────────────────────────────────────────┘
                            │
   ┌────────────────────────▼────────────────────────────────────────────┐
   │ STAGE 3  INJECT            load.py  ·  schema.sql                   │
   │                                                                     │
   │   Loads each file into its table. Reconciles columns first: new     │
   │   ones are added NULLABLE with no backfill. Nothing is ever         │
   │   dropped or retyped automatically.                                 │
   └─────────────────────────────────────────────────────────────────────┘
```

```bash
# everything, end to end
python3 pipeline.py --src "../Project Drafts/data and video" \
                    --dsn "postgresql://routemind:pass@localhost:5432/routemind"

python3 pipeline.py --src "<dir>" --stage 1     # the gate alone
python3 pipeline.py --src "<dir>"               # stages 1-2, stop before the DB
python3 pipeline.py --src "<dir>" --strict      # warnings stop the run too
python3 pipeline.py --src "<dir>" --dsn ... --truncate   # full reload, not append
python3 pipeline.py --src "<dir>" --dsn ... --force      # ingest despite a failure
```

Exit codes: `0` pass · `1` blocked. Safe to wire into CI or a scheduler.

Needs `pandas` + `pyyaml` for stages 1–2 and `psql` on the PATH for stage 3.

---

## STAGE 1 — validate

Each month arrives as a fresh drop and can differ from the last. A renamed column, a new
`delay_reason`, a truncated export or a dtype drift would all **ingest cleanly and quietly
corrupt the metrics**. The gate turns a silent wrong number into a loud, specific failure.

`contracts.yml` declares what each file must look like — columns, types, category values,
null-rate baselines, numeric ranges, uniqueness, row-count floors. Every rule in it was
learned from the real data.

Checks: missing / unexpected columns · type parseability · **new category values** ·
null-rate drift · numeric range · duplicate keys · row-count sanity · date-range vs month.

**Tested against a deliberately broken drop** — `vendor_id` renamed to `supplier_id`, a new
`delay_reason=WEATHER`, a new `product_type=RENTAL_X`, and an extra column. The gate caught
all four and stopped the pipeline.

### What the LLM does here — and what it deliberately does not

> **It does not decide pass or fail.** A model gating ingestion would mean the same file
> could pass on Tuesday and fail on Wednesday, and a wrong number would be untraceable —
> which is the exact failure this pipeline exists to prevent. The verdict is always the
> deterministic rules.

It interprets the findings a rule cannot judge, because the input is fuzzy and there is no
right answer to hard-code:

| Finding | What the model is asked |
|---|---|
| unfamiliar new column | what does `co2_grams` mean, is it worth adopting, what metric could it support? |
| new value in a known category | is `WEATHER` a real new delay reason, or a typo for an existing one? |
| a flagged anomaly | is a 1,093 km "planned" trip plausible, or an artefact? |

It answers in a fixed four-line form — `MEANING / RECOMMEND / WHY / METRIC` — and **a human
approves every one**. If no key is set or the call fails, a deterministic heuristic answers
instead and the exit code is byte-identical. The LLM is an enhancement, never a dependency.

```bash
export ROUTEMIND_LLM_KEY=...      # Sarvam or any OpenAI-compatible endpoint
```

Cost is irrelevant: this runs once per new column *ever*, not once per row.

### When a NEW column appears

A new column is not an error; it's a decision. The pipeline surfaces it, proposes what to
do, and lets you act **from the UI**.

```
validate.py detects it
   └─ profiles it (type, % populated, distinct count, samples, first date seen)
       └─ POSTs to the service  →  Schema tab shows it as PENDING
           └─ proposal from the LLM (or the heuristic)
               ├─ Adopt   → ALTER TABLE … ADD COLUMN (nullable, no backfill)
               ├─ Ignore  → recorded, never warned about again
               └─ Reject  → future drops containing it fail validation
```

Decisions travel back: `pipeline.py` pulls `/api/schema/decisions` into
`schema-decisions.json` at the start of every run. Without that round trip a column you
adopted last month would be dropped again this month.

### Backward compatibility — the guarantee

1. New columns are added **NULLABLE, with no default and no backfill**. Historical rows
   read NULL, which means *"not collected then"* — never `0`, never `''`.
2. Nothing is dropped or retyped automatically; those need a human migration.
3. Every adopted column records **`availableFrom`** — the first date it has data. Anything
   computing over it must scope to that date, or it will average across a period where the
   column did not exist and silently report a wrong number.
4. Existing queries never `SELECT *`, and the load names its columns explicitly, so a new
   column **cannot change an existing metric's value**.
5. New *values* in an existing category need no migration at all — categories are TEXT.

---

## STAGE 2 — clean

`rules.yml` is the spec; `clean.py` is only an interpreter for it. That means a reviewer
can audit exactly what happens to the data without reading any Python, and a new rule is a
YAML edit rather than a code change.

Every rule carries a `why:` pointing at the organisers' data dictionary or a STAGE 1
finding. A sample:

| Problem | Fix | Traced to |
|---|---|---|
| `trip_id` is `"1,516,906"` in ride/feedback/alerts but `1516906` in emp/bill | strip commas → `BIGINT` — **this is what makes the files joinable** | dictionary quirk 1 |
| `bill.trip_id` contains the literal `"OverHead"` | `trip_id = NULL`, `is_overhead = true`; kept so total spend stays whole | file profiling |
| negative `trip_cost` | `is_penalty = true` — an SLA penalty deducted from the vendor | confirmed by the team |
| three date formats across five files | parsed per file | dictionary quirk 3 |
| epochs as `"1,782,864,900"` and as `1783633500.0` | stripped, cast → `TIMESTAMPTZ` (UTC) | dictionary quirk 4 |
| negative distances down to −6.63 km | `NULL` below 0.01 | dictionary quirk 6 |
| `alerts.severity` contains a literal `False` | → `NULL` | `alerts_data.md` |
| ratings use `0` to mean "not rated" | → `NULL`, so averages aren't dragged down | file profiling |
| `stwid = 0` placeholder | → `NULL` | dictionary quirk 2 |
| `trip_nodal = 'NA'` | → `NULL` | file profiling |

**Not de-duplicated, deliberately.** `trip_id` is unique within a month but 6,753 ids recur
across months. Treating those as duplicates silently discarded **1.1% of real trips**, so
the table uses a surrogate primary key and `trip_id` is merely indexed. Whether those are
genuinely distinct trips is [open question A5](../Project%20Drafts/open-questions.md).

An **adopted** column that `rules.yml` does not yet map is carried through as raw text
rather than dropped. A column nobody has decided on is neither carried nor silently
ignored — it blocks on a human, which is the point.

---

## STAGE 3 — inject

Before loading, the CSV's columns are compared with the live table's:

- a column the CSV has and the table does not → `ADD COLUMN IF NOT EXISTS … TEXT`, nullable
- a column the table has and the CSV does not → left untouched; new rows simply get NULL
- the `\copy` names its columns explicitly, so adding one can never shift another's meaning

```bash
python3 load.py --dsn "postgresql://routemind:pass@localhost:5432/routemind" \
                --dir ./normalized --dry-run     # show the plan, change nothing
```

## What comes out

| Table | Rows | Grain |
|---|---|---|
| `trips` | **615,546** | one per trip (May–Jul 2026) |
| `trip_employees` | **1,637,906** | one per employee per trip |
| `billing` | **620,942** | one per billed line |
| `feedback` | **512,873** | one per feedback response |
| `alerts` | **51,699** | one per safety/ops event |

All five match the row counts in the organisers' data dictionary exactly.

## What the gate found in the *real* data

Three things that manual profiling and the official dictionary both missed:

| Finding | Detail |
|---|---|
| **SLA penalties** | 189 billing lines are negative, totalling **−₹15.5M** (largest −₹2,233,333), across 9 of 24 vendors and only 3 contracts. So ₹834M is *net*; gross is ₹849.5M. |
| **3 undocumented alert types** | `PANIC_MOBILE` (202), `FIRST_MALE_NO_SHOW` (130), `SUPPLEMENTARY_ALERT` (1) — 11 types in total, not the 8 profiled by hand. |
| **Extreme distances** | `planned_km` up to 1,093 km, and a negative (−2.0) in the *trips* file — not only in `emp_data` as the dictionary warns. |

## Checking the numbers

`verify_claims.py` regenerates every figure quoted in the deck and the docs straight from
the cleaned CSVs, so nothing is repeated from memory. Where two definitions are both
defensible — spend net of penalties vs gross — it prints both and marks the headline one.

```bash
python3 verify_claims.py
```

## Note on stack

This bulk load is Python because that is the fastest way to get the data in. The live
onboarding path in the service implements the same rules in Java — **`rules.yml` is the
spec, not the language.** `normalize.py` is a deprecated shim that forwards to `clean.py`.
