"""Schema validation gate — run BEFORE extracting any new monthly drop.

The point: a new month can silently differ from the last one. A renamed column, a new
`delay_reason`, a dtype drift, a truncated export — each would sail through ingestion and
quietly corrupt the metrics. This checks the incoming files against `contracts.yml` and
returns a clear PASS / WARN / FAIL so the pipeline can stop before damage is done.

    python validate.py --src "<raw dir>"                 # validate everything
    python validate.py --src "<dir>" --source trips      # one source
    python validate.py --src "<dir>" --json report.json  # machine-readable

Exit codes:  0 = pass (or warnings only)   1 = failed   2 = could not run
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from dataclasses import dataclass, asdict, field
from typing import Any

import pandas as pd
import yaml

from llm_advisor import Advisor

SAMPLE_ROWS = 200_000       # validate on a sample; enough to catch structural drift

# The codes an LLM can usefully interpret. Everything else is a rule with one right
# answer — a missing required column is a FAIL whatever a model thinks of it.
EXPLAINABLE = {"UNEXPECTED_COLUMN", "NEW_ENUM_VALUE", "ABOVE_MAX", "OUTLIERS"}

FAIL, WARN, INFO = "FAIL", "WARN", "INFO"


@dataclass
class Issue:
    severity: str
    source: str
    column: str | None
    code: str
    message: str
    # extra payload consumed by /api/schema/report (profile of a new column,
    # the new enum values, the date from which the column has data)
    profile: dict | None = None
    values: list | None = None
    availableFrom: str | None = None
    # LLM (or heuristic) interpretation of what this change means and what to do.
    # Advisory only — it never changes the severity or the verdict.
    advice: str | None = None


@dataclass
class Report:
    issues: list[Issue] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)

    def add(self, sev, source, column, code, message, **extra):
        self.issues.append(Issue(sev, source, column, code, message, **extra))

    @property
    def failed(self) -> bool:
        return any(i.severity == FAIL for i in self.issues)

    def counts(self):
        return {s: sum(1 for i in self.issues if i.severity == s) for s in (FAIL, WARN, INFO)}


# ---------------------------------------------------------------- parsers
def _strip_commas(s: pd.Series) -> pd.Series:
    return s.astype(str).str.replace(",", "", regex=False).str.strip()


def parse_as(series: pd.Series, kind: str, spec: dict) -> tuple[pd.Series, pd.Series]:
    """Return (parsed, ok_mask). ok_mask is False where the value could not be parsed."""
    raw = series
    nonnull = raw.notna() & (raw.astype(str).str.strip() != "")

    if kind in ("id_comma", "id_plain", "number_comma", "epoch_comma", "epoch_float",
                "number", "int"):
        v = pd.to_numeric(_strip_commas(raw), errors="coerce")
        return v, (~v.isna()) | ~nonnull

    if kind == "id_or_token":
        v = pd.to_numeric(_strip_commas(raw), errors="coerce")
        tokens = set(spec.get("allowed_tokens", []))
        is_token = raw.astype(str).str.strip().isin(tokens)
        return v, (~v.isna()) | is_token | ~nonnull

    if kind == "bool":
        m = raw.astype(str).str.strip().str.lower()
        ok = m.isin({"true", "false", "1", "0", "yes", "no"})
        return m, ok | ~nonnull

    if kind == "date":
        v = pd.to_datetime(raw, errors="coerce", format=spec.get("_fmt"))
        return v, (~v.isna()) | ~nonnull

    if kind == "datetime_long":
        v = pd.to_datetime(raw, errors="coerce", format="%B %d, %Y, %I:%M %p")
        return v, (~v.isna()) | ~nonnull

    return raw, pd.Series(True, index=raw.index)      # string / enum


def _infer_type(s: pd.Series) -> str:
    """Best-effort type for a column we have never seen before."""
    nn = s.dropna()
    if nn.empty:
        return "TEXT"
    v = pd.to_numeric(nn.astype(str).str.replace(",", "", regex=False), errors="coerce")
    if v.notna().all():
        return "INTEGER" if (v.dropna() % 1 == 0).all() else "NUMBER"
    if nn.astype(str).str.lower().isin({"true", "false", "yes", "no", "0", "1"}).all():
        return "BOOLEAN"
    if pd.to_datetime(nn, errors="coerce").notna().mean() > 0.9:
        return "TIMESTAMP"
    return "TEXT"


def load_decisions(path: str | None) -> dict:
    """Decisions made in the UI, so a settled column stops being a surprise."""
    if not path or not os.path.exists(path):
        return {}
    try:
        return json.load(open(path))
    except Exception:
        return {}


# ---------------------------------------------------------------- checks
def validate_source(name: str, spec: dict, src_dir: str, rep: Report,
                    decisions: dict) -> None:
    files = sorted(glob.glob(os.path.join(src_dir, spec["file_glob"])))
    if not files:
        rep.add(FAIL, name, None, "NO_FILE",
                f"no file matching {spec['file_glob']!r} in {src_dir}")
        return

    total_rows = 0
    for path in files:
        fname = os.path.basename(path)
        try:
            df = pd.read_csv(path, nrows=SAMPLE_ROWS, low_memory=False)
            full_rows = sum(1 for _ in open(path, encoding="utf-8", errors="ignore")) - 1
        except Exception as e:
            rep.add(FAIL, name, None, "UNREADABLE", f"{fname}: {e}")
            continue
        total_rows += full_rows

        # ---- row-count sanity
        lo, hi = spec.get("min_rows"), spec.get("max_rows")
        if lo and full_rows < lo:
            rep.add(FAIL, name, None, "TOO_FEW_ROWS",
                    f"{fname}: {full_rows:,} rows < minimum {lo:,} — truncated export?")
        if hi and full_rows > hi:
            rep.add(WARN, name, None, "MORE_ROWS_THAN_EXPECTED",
                    f"{fname}: {full_rows:,} rows > expected max {hi:,}")

        declared = spec["columns"]
        present = set(df.columns)

        # ---- missing / unexpected columns  (the #1 thing that breaks a new month)
        for col, cspec in declared.items():
            if col not in present:
                sev = FAIL if cspec.get("required") else WARN
                rep.add(sev, name, col, "MISSING_COLUMN",
                        f"{fname}: declared column '{col}' is absent")
        for col in present - set(declared):
            decided = decisions.get(name, {}).get(col)
            if decided:                      # already adopted/ignored via the UI
                rep.add(INFO, name, col, "KNOWN_NEW_COLUMN",
                        f"{fname}: '{col}' already {decided.get('state','decided').lower()}"
                        f" (from {decided.get('availableFrom')})")
                continue
            s_col = df[col]
            prof = {
                "type": _infer_type(s_col),
                "nonNullPct": round(float(s_col.notna().mean()) * 100, 1),
                "distinct": int(s_col.nunique(dropna=True)),
                "samples": [str(v) for v in s_col.dropna().unique()[:5]],
            }
            first_date = None
            dc = spec.get("date_column")
            if dc and dc in present:
                d = pd.to_datetime(df.loc[s_col.notna(), dc], errors="coerce",
                                   format=spec.get("date_format"))
                if d.notna().any():
                    first_date = str(d.min().date())
            rep.add(WARN, name, col, "UNEXPECTED_COLUMN",
                    f"{fname}: new column '{col}' not in the contract — decide in the UI",
                    profile=prof, availableFrom=first_date)

        # ---- per-column checks
        for col, cspec in declared.items():
            if col not in present:
                continue
            s = df[col]
            kind = cspec.get("type", "string")
            if kind == "date":
                cspec = {**cspec, "_fmt": spec.get("date_format")}

            parsed, ok = parse_as(s, kind, cspec)
            bad = int((~ok).sum())
            if bad:
                pct = bad / len(df) * 100
                sev = FAIL if pct > 1 else WARN
                sample = s[~ok].dropna().astype(str).head(3).tolist()
                rep.add(sev, name, col, "TYPE_MISMATCH",
                        f"{fname}: {bad:,} value(s) ({pct:.2f}%) not parseable as "
                        f"{kind}; e.g. {sample}")

            # null rate vs the declared baseline
            declared_null = cspec.get("null_pct")
            actual_null = float(s.isna().mean())
            if declared_null is not None:
                tol = 0.05
                if actual_null > declared_null + tol:
                    rep.add(WARN, name, col, "NULL_RATE_DRIFT",
                            f"{fname}: nulls {actual_null*100:.1f}% vs expected "
                            f"~{declared_null*100:.1f}%")

            # enum drift — a NEW value is the classic silent breaker
            if kind == "enum":
                allowed = set(map(str, cspec.get("values", [])))
                tolerated = set(map(str, cspec.get("tolerated_bad", [])))
                seen = set(s.dropna().astype(str).str.strip().unique())
                unknown = seen - allowed - tolerated
                if unknown:
                    decided = decisions.get(name, {}).get(col)
                    if decided:
                        rep.add(INFO, name, col, "KNOWN_NEW_ENUM",
                                f"{fname}: new value(s) previously "
                                f"{decided.get('state','decided').lower()}")
                    else:
                        rep.add(WARN, name, col, "NEW_ENUM_VALUE",
                                f"{fname}: unseen value(s) {sorted(unknown)[:5]} — "
                                f"decide in the UI before ingesting",
                                values=sorted(unknown)[:20])
                if tolerated & seen:
                    rep.add(INFO, name, col, "KNOWN_BAD_VALUE",
                            f"{fname}: contains known-bad {sorted(tolerated & seen)} "
                            f"(cleaned to NULL downstream)")

            # numeric range
            if kind in ("int", "number", "number_comma") and parsed.notna().any():
                lo_v, hi_v = cspec.get("min"), cspec.get("max")
                if lo_v is not None and (parsed < lo_v).any():
                    n = int((parsed < lo_v).sum())
                    sev = INFO if cspec.get("known_bad_negatives") else WARN
                    rep.add(sev, name, col, "BELOW_MIN",
                            f"{fname}: {n:,} value(s) < {lo_v} (min {parsed.min():.2f})")
                if hi_v is not None and (parsed > hi_v).any():
                    n = int((parsed > hi_v).sum())
                    rep.add(WARN, name, col, "ABOVE_MAX",
                            f"{fname}: {n:,} value(s) > {hi_v} (max {parsed.max():,.0f})")
                out = cspec.get("outlier_above")
                if out is not None and (parsed > out).any():
                    n = int((parsed > out).sum())
                    rep.add(INFO, name, col, "OUTLIERS",
                            f"{fname}: {n:,} value(s) > {out} — winsorised downstream")

            # uniqueness
            if cspec.get("unique_in_file") and parsed.notna().any():
                dupes = int(parsed.duplicated().sum())
                if dupes:
                    rep.add(FAIL, name, col, "DUPLICATE_KEY",
                            f"{fname}: {dupes:,} duplicate values in a column declared unique")

        # ---- date range should match the file's month
        dc = spec.get("date_column")
        if dc and dc in present:
            d = pd.to_datetime(df[dc], errors="coerce", format=spec.get("date_format"))
            if d.notna().any():
                rep.stats.setdefault(f"{name}:{fname}", {})["date_range"] = \
                    f"{d.min().date()} → {d.max().date()}"
                if d.dt.to_period("M").nunique() > 1:
                    rep.add(WARN, name, dc, "MULTI_MONTH_FILE",
                            f"{fname}: spans {d.dt.to_period('M').nunique()} months")

    rep.stats.setdefault(name, {})["rows"] = total_rows
    rep.stats[name]["files"] = [os.path.basename(f) for f in files]


# ---------------------------------------------------------------- runner
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="directory holding the raw monthly files")
    ap.add_argument("--contracts", default=os.path.join(os.path.dirname(__file__), "contracts.yml"))
    ap.add_argument("--source", help="validate only this source")
    ap.add_argument("--json", help="write the report as JSON")
    ap.add_argument("--strict", action="store_true", help="treat warnings as failure")
    ap.add_argument("--decisions", default=os.path.join(os.path.dirname(__file__),
                                                        "schema-decisions.json"),
                    help="decisions made in the UI (GET /api/schema/decisions)")
    ap.add_argument("--no-explain", action="store_true",
                    help="skip the LLM/heuristic interpretation of flagged changes")
    a = ap.parse_args()

    try:
        contracts = yaml.safe_load(open(a.contracts))
    except Exception as e:
        print(f"cannot read contracts: {e}")
        return 2

    decisions = load_decisions(a.decisions)
    rep = Report()
    sources = contracts["sources"]
    if a.source:
        sources = {a.source: sources[a.source]} if a.source in sources else {}
        if not sources:
            print(f"unknown source '{a.source}'")
            return 2

    for name, spec in sources.items():
        validate_source(name, spec, a.src, rep, decisions)

    # ---- interpret the fuzzy findings
    #
    # The verdict is ALREADY decided at this point, by the deterministic rules above.
    # This step only attaches an explanation to the findings a rule cannot judge, so the
    # human deciding in the UI has something to react to. If the model is unavailable the
    # heuristic answers instead and the exit code is identical either way.
    advisor = Advisor()
    if not a.no_explain:
        for i in rep.issues:
            if i.code not in EXPLAINABLE:
                continue
            if i.code == "UNEXPECTED_COLUMN" and i.profile:
                i.advice = advisor.explain_new_column(i.source, i.column, i.profile)
            elif i.code == "NEW_ENUM_VALUE" and i.values:
                known = contracts["sources"][i.source]["columns"][i.column].get("values")
                i.advice = advisor.explain_new_values(i.source, i.column, i.values, known)
            else:
                i.advice = advisor.explain_anomaly(i.source, i.column, i.message)

    # ---- print
    print("=" * 74)
    print("SCHEMA VALIDATION")
    print("=" * 74)
    for name, st in rep.stats.items():
        if "rows" in st:
            print(f"  {name:16} {st['rows']:>10,} rows   {', '.join(st.get('files', []))}")
    print()
    order = {FAIL: 0, WARN: 1, INFO: 2}
    for i in sorted(rep.issues, key=lambda x: order[x.severity]):
        mark = {"FAIL": "❌", "WARN": "⚠️ ", "INFO": "ℹ️ "}[i.severity]
        where = f"{i.source}.{i.column}" if i.column else i.source
        print(f"  {mark} [{i.code}] {where}: {i.message}")
        if i.advice:
            for line in i.advice.splitlines():
                if line.strip():
                    print(f"        │ {line}")

    c = rep.counts()
    print()
    print("-" * 74)
    print(f"  {c[FAIL]} failures · {c[WARN]} warnings · {c[INFO]} notes")
    if not a.no_explain:
        print(f"  interpretation by: {'LLM' if advisor.available else 'heuristic (no key set)'}"
              f" — advisory only, the verdict is from the rules")
    verdict = "FAILED" if rep.failed else ("PASSED WITH WARNINGS" if c[WARN] else "PASSED")
    print(f"  VERDICT: {verdict}")
    print("-" * 74)

    if a.json:
        with open(a.json, "w") as f:
            json.dump({"issues": [asdict(i) for i in rep.issues],
                       "stats": rep.stats, "counts": c, "verdict": verdict}, f, indent=2)
        print(f"  report written to {a.json}")

    if rep.failed:
        return 1
    if a.strict and c[WARN]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
