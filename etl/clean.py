"""STAGE 2 — cleaning. Interprets `rules.yml`; contains no cleaning decisions of its own.

    raw monthly CSVs  ──►  clean.py (driven by rules.yml)  ──►  normalized/*.csv

Every rule lives in `rules.yml` with a `why:` citing the organisers' data dictionary or a
finding from STAGE 1. That separation is the point: a reviewer can audit what happens to
the data by reading YAML, and adding a rule never means editing this file.

Backward compatibility with new columns
---------------------------------------
A column present in the raw drop but absent from `rules.yml` is NOT silently dropped and
NOT silently ingested. STAGE 1 raises it for a human, who decides in the UI; the decision
lands in `schema-decisions.json`. Only ADOPTED columns are carried through here, appended
after the mapped ones, as raw text — the load stage adds them to the table as nullable.
Historical rows keep reading NULL, which means "not collected then", never 0 and never "".

    python clean.py --src "<raw dir>" --out ./normalized
    python clean.py --src "<dir>" --out ./normalized --only trips
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys

import numpy as np
import pandas as pd
import yaml

HERE = os.path.dirname(os.path.abspath(__file__))


# --------------------------------------------------------------- transforms
def _strip(s: pd.Series) -> pd.Series:
    return s.astype(str).str.replace(",", "", regex=False).str.strip()


def t_text(s, spec):
    v = s.astype("string").str.strip()
    return v.where(v.ne("") & v.ne("nan"))


def t_bigint(s, spec):
    return pd.to_numeric(_strip(s), errors="coerce").astype("Int64")


def t_id_nullable(s, spec):
    v = t_bigint(s, spec)
    return v.where(v != 0)                      # 0 is a documented placeholder rider


def t_number(s, spec):
    return pd.to_numeric(_strip(s), errors="coerce")


def t_int(s, spec):
    return pd.to_numeric(_strip(s), errors="coerce").astype("Int64")


def t_km(s, spec):
    v = t_number(s, spec)
    return v.where(v >= 0.01)                   # negatives are physically impossible


def t_rating(s, spec):
    v = t_number(s, spec)
    return v.where(v.between(1, 5)).astype("Int64")   # 0 = not rated


def t_bool(s, spec):
    m = {"true": True, "false": False, "1": True, "0": False, "yes": True, "no": False}
    return s.astype(str).str.strip().str.lower().map(m).astype("boolean")


def t_epoch_ts(s, spec):
    return pd.to_datetime(t_number(s, spec), unit="s", utc=True, errors="coerce")


def t_date(s, spec):
    return pd.to_datetime(s.astype(str).str.strip(), format=spec.get("format"),
                          errors="coerce", utc=True).dt.date


def t_datetime(s, spec):
    return pd.to_datetime(s.astype(str).str.strip(), format=spec.get("format"),
                          errors="coerce", utc=True)


def t_na_to_null(s, spec):
    v = t_text(s, spec)
    return v.where(v.ne("NA"))


def t_severity(s, spec):
    v = t_text(s, spec)
    return v.where(v.str.startswith("Sev", na=False))   # a stray literal "False" exists


TRANSFORMS = {
    "text": t_text, "bigint": t_bigint, "id_nullable": t_id_nullable,
    "number": t_number, "int": t_int, "km": t_km, "rating_1_5": t_rating,
    "bool": t_bool, "epoch_ts": t_epoch_ts, "date": t_date, "datetime": t_datetime,
    "na_to_null": t_na_to_null, "severity": t_severity,
}

def _line_kind(out: pd.DataFrame, ch: pd.DataFrame) -> pd.Series:
    """Classify a billing line. See rules.yml for why is_overhead alone isn't enough."""
    cost = pd.to_numeric(_strip(ch["trip_cost"]), errors="coerce")
    monthly = out["trip_id"].isna()          # the literal "OverHead" rows
    negative = cost.lt(0)
    return pd.Series(
        np.select(
            [monthly & negative, monthly & ~negative, ~monthly & negative],
            ["MONTHLY_PENALTY", "FIXED_CHARGE", "TRIP_PENALTY"],
            default="TRIP"),
        index=ch.index, dtype="object")


def _shift_band(out: pd.DataFrame, ch: pd.DataFrame) -> pd.Series:
    """Group the 100 distinct shift times into 6 operational bands.

    SLAs cannot realistically be configured against 100 clock times — that would be 1,316
    vendor x cab-type x shift combinations. Bands cut it to 163, of which 147 carry enough
    trips to judge. They are not arbitrary: on-time performance separates sharply across
    them, from 92.3% (MORNING) to 99.4% (EARLY), a 7.1-point spread.

    'Non Shift' and 'Adhoc' are not clock times at all and get their own band — together
    14,799 trips running at 86.9%, the worst of any band. Folding them into a time band
    would have hidden that.
    """
    s = ch["shift_type"].astype(str).str.strip()
    hour = pd.to_numeric(s.str.slice(0, 2), errors="coerce")
    band = pd.Series("UNSCHEDULED", index=ch.index, dtype="object")
    band = band.mask(hour.between(22, 23) | hour.between(0, 3), "NIGHT")
    band = band.mask(hour.between(4, 7), "EARLY")
    band = band.mask(hour.between(8, 11), "MORNING")
    band = band.mask(hour.between(12, 16), "MIDDAY")
    band = band.mask(hour.between(17, 21), "EVENING")
    return band


DERIVED = {
    "trip_id_is_null": lambda out, ch: out["trip_id"].isna(),
    "shift_band": _shift_band,
    "cost_is_negative": lambda out, ch: pd.to_numeric(
        _strip(ch["trip_cost"]), errors="coerce").lt(0),
    "line_kind": _line_kind,
}


# --------------------------------------------------------------- the engine
class Cleaner:
    def __init__(self, rules: dict, decisions: dict):
        self.rules = rules
        self.decisions = decisions
        self.chunk = int(rules.get("chunk_rows", 200_000))

    def adopted_extra(self, source: str, present: set[str], mapped: set[str]) -> list[str]:
        """Columns a human ADOPTED in the UI that rules.yml does not yet map."""
        decided = self.decisions.get(source, {})
        return [c for c in present
                if c not in mapped and decided.get(c, {}).get("state") == "ADOPTED"]

    def clean_chunk(self, source: str, spec: dict, ch: pd.DataFrame,
                    warn: set[str]) -> pd.DataFrame:
        out = pd.DataFrame(index=ch.index)
        mapped: set[str] = set()

        for target, cspec in spec["columns"].items():
            if "derive" in cspec:
                continue                        # derived columns need the mapped ones first
            src_col = cspec["from"]
            mapped.add(src_col)
            if src_col not in ch.columns:
                # STAGE 1 already failed the run for a required missing column; if we are
                # here it was optional, so emit NULLs rather than crashing the load.
                if src_col not in warn:
                    print(f"    ! {source}.{src_col} absent — emitting NULL", flush=True)
                    warn.add(src_col)
                out[target] = pd.Series(pd.NA, index=ch.index, dtype="object")
                continue
            fn = TRANSFORMS.get(cspec.get("transform", "text"))
            if fn is None:
                raise SystemExit(f"rules.yml: unknown transform "
                                 f"{cspec.get('transform')!r} on {source}.{target}")
            out[target] = fn(ch[src_col], cspec)

        for target, cspec in spec["columns"].items():
            if "derive" in cspec:
                fn = DERIVED.get(cspec["derive"])
                if fn is None:
                    raise SystemExit(f"rules.yml: unknown derive {cspec['derive']!r}")
                out[target] = fn(out, ch)

        # ---- adopted-but-unmapped columns ride along as raw text
        for extra in self.adopted_extra(source, set(ch.columns), mapped):
            if extra not in warn:
                print(f"    + carrying adopted column '{extra}' through as text", flush=True)
                warn.add(extra)
            out[extra] = t_text(ch[extra], {})

        for col in spec.get("drop_rows_where_null") or []:
            out = out[out[col].notna()]

        # column order must match what STAGE 3 will COPY into
        ordered = list(spec["columns"].keys())
        ordered += [c for c in out.columns if c not in ordered]
        return out[ordered]

    def run_source(self, source: str, src_dir: str, out_dir: str) -> tuple[int, list[str]]:
        spec = self.rules["sources"][source]
        files = sorted(glob.glob(os.path.join(src_dir, spec["files"])))
        if not files:
            raise SystemExit(f"no file matching {spec['files']!r} in {src_dir}")

        dest = os.path.join(out_dir, f"{spec['table']}.csv")
        first, total, warn = True, 0, set()
        seen_keys = set()
        dedupe = spec.get("dedupe")
        cols: list[str] = []

        for path in files:
            for ch in pd.read_csv(path, chunksize=self.chunk, low_memory=False):
                d = self.clean_chunk(source, spec, ch, warn)
                if dedupe:
                    key = d[dedupe].astype(str).agg("|".join, axis=1)
                    d = d[~key.isin(seen_keys)]
                    seen_keys.update(key)
                cols = list(d.columns)
                d.to_csv(dest, index=False, mode="w" if first else "a", header=first)
                first, total = False, total + len(d)
        return total, cols


def load_decisions(path: str) -> dict:
    if not os.path.exists(path):
        return {}
    try:
        return json.load(open(path))
    except Exception:
        return {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="directory of raw monthly files")
    ap.add_argument("--out", default=os.path.join(HERE, "normalized"))
    ap.add_argument("--rules", default=os.path.join(HERE, "rules.yml"))
    ap.add_argument("--decisions", default=os.path.join(HERE, "schema-decisions.json"))
    ap.add_argument("--only", help="one source name from rules.yml")
    ap.add_argument("--manifest", default=None,
                    help="write the emitted column list here for STAGE 3")
    a = ap.parse_args()

    rules = yaml.safe_load(open(a.rules))
    cleaner = Cleaner(rules, load_decisions(a.decisions))
    os.makedirs(a.out, exist_ok=True)

    names = [a.only] if a.only else list(rules["sources"])
    manifest = {}
    for name in names:
        if name not in rules["sources"]:
            raise SystemExit(f"unknown source {name!r}; have {list(rules['sources'])}")
        n, cols = cleaner.run_source(name, a.src, a.out)
        table = rules["sources"][name]["table"]
        manifest[table] = cols
        print(f"  {name:16s} -> {n:>10,} rows  ({len(cols)} columns)", flush=True)

    path = a.manifest or os.path.join(a.out, "_manifest.json")
    json.dump(manifest, open(path, "w"), indent=2)
    print(f"  manifest -> {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
