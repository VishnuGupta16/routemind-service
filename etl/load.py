"""STAGE 3 — injection. Loads the cleaned CSVs into the right table, backward compatibly.

    normalized/*.csv  ──►  reconcile columns  ──►  \\copy  ──►  row-count check

The backward-compatibility contract, enforced here rather than assumed:

  1. Before loading, the live table's columns are compared with the CSV's. Any column the
     CSV has and the table does not is added with `ALTER TABLE … ADD COLUMN IF NOT EXISTS
     <col> TEXT` — nullable, no default, NO BACKFILL. Historical rows therefore read NULL,
     which means "not collected then". Never 0, never "".
  2. Nothing is ever dropped or retyped here. A column the table has and the CSV does not
     simply isn't in the COPY list, so existing rows keep their values and new rows get
     NULL. A drop or a type change is a human migration, deliberately not automated.
  3. New *values* in an existing category column need no action at all — categories are
     stored as TEXT, so a `delay_reason` of WEATHER loads like any other. STAGE 1 still
     raises it, because a rule or a breakdown that enumerates values may need updating.
  4. The COPY names its columns explicitly. Adding a column can therefore never shift the
     meaning of an existing one, which is what a positional COPY would risk.

Only ADOPTED columns ever reach this stage — clean.py filters on the human decisions in
`schema-decisions.json`, so nothing is added to the database that a person did not approve.

Uses `psql` rather than a driver, so the pipeline needs no database library installed.

    python load.py --dsn postgresql://routemind:pass@localhost:5432/routemind \\
                   --dir ./normalized
    python load.py --dsn ... --dir ./normalized --dry-run     # print the plan only
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Load order matters only for readability — there are no FK constraints between these
# tables, deliberately: a billing line can legitimately arrive for a trip we have not
# ingested yet, and refusing it would lose real spend.
TABLES = ["trips", "trip_employees", "billing", "feedback", "alerts"]


def psql(dsn: str, *args: str, capture: bool = False, check: bool = True):
    cmd = ["psql", dsn, "-v", "ON_ERROR_STOP=1", *args]
    if capture:
        r = subprocess.run(cmd + ["-tAq"], capture_output=True, text=True)
        if check and r.returncode != 0:
            raise SystemExit(f"psql failed: {r.stderr.strip()}")
        return r.stdout.strip()
    return subprocess.call(cmd)


def csv_columns(path: str) -> list[str]:
    with open(path, newline="", encoding="utf-8") as fh:
        return next(csv.reader(fh))


def table_columns(dsn: str, table: str) -> list[str]:
    out = psql(dsn, "-c",
               "SELECT column_name FROM information_schema.columns "
               f"WHERE table_name = '{table}' ORDER BY ordinal_position", capture=True)
    return [line.strip() for line in out.splitlines() if line.strip()]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True)
    ap.add_argument("--dir", default=os.path.join(HERE, "normalized"))
    ap.add_argument("--schema", default=os.path.join(HERE, "schema.sql"))
    # Configuration and report history. Applied every run and never dropped — schema.sql
    # rebuilds the data tables, this one must survive that.
    ap.add_argument("--admin-schema", default=os.path.join(HERE, "schema_admin.sql"))
    ap.add_argument("--truncate", action="store_true",
                    help="empty the tables first (full reload rather than append)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only", help="one table name")
    a = ap.parse_args()

    tables = [a.only] if a.only else TABLES
    plan: list[tuple[str, str, list[str], list[str]]] = []

    if not a.dry_run:
        print("==> ensuring data schema exists")
        if psql(a.dsn, "-f", a.schema) != 0:
            return 1
        print("==> ensuring application schema exists (personas, alerts, reports)")
        if os.path.exists(a.admin_schema) and psql(a.dsn, "-f", a.admin_schema) != 0:
            return 1

    # ---------------------------------------------- reconcile before loading
    for table in tables:
        path = os.path.join(a.dir, f"{table}.csv")
        if not os.path.exists(path):
            print(f"  - {table}: no CSV in {a.dir}, skipping")
            continue
        incoming = csv_columns(path)
        existing = [] if a.dry_run else table_columns(a.dsn, table)
        new = [c for c in incoming if existing and c not in existing]
        gone = [c for c in existing if c not in incoming and c not in ("id",)]
        plan.append((table, path, new, gone))

    print("\n==> column reconciliation")
    for table, _, new, gone in plan:
        if not new and not gone:
            print(f"  = {table}: schema matches")
        if new:
            print(f"  + {table}: {len(new)} new column(s) -> added NULLABLE, no backfill: {new}")
        if gone:
            print(f"  ~ {table}: {len(gone)} column(s) absent from this drop -> "
                  f"left untouched, new rows get NULL: {gone}")

    if a.dry_run:
        print("\ndry run — nothing was written.")
        return 0

    for table, _, new, _ in plan:
        for col in new:
            safe = "".join(ch if (ch.isalnum() or ch == "_") else "_" for ch in col.lower())
            if not safe or safe[0].isdigit():
                safe = "c_" + safe
            ddl = f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {safe} TEXT"
            print(f"  $ {ddl}")
            if psql(a.dsn, "-c", ddl) != 0:
                return 1

    # ------------------------------------------------------------- the load
    print("\n==> loading")
    for table, path, _, _ in plan:
        if a.truncate:
            psql(a.dsn, "-c", f"TRUNCATE {table}")
        cols = ", ".join(csv_columns(path))
        print(f"  -> {table}")
        rc = psql(a.dsn, "-c",
                  f"\\copy {table} ({cols}) FROM '{path}' "
                  f"WITH (FORMAT csv, HEADER true, NULL '')")
        if rc != 0:
            print(f"  ⛔ load of {table} failed")
            return 1

    # ------------------------------------------------------------- verify
    print("\n==> row counts")
    union = "\nUNION ALL ".join(
        f"SELECT '{t}' AS table_name, count(*) FROM {t}" for t, _, _, _ in plan)
    psql(a.dsn, "-c", union)

    # The vendor list SLAs are configured against is derived from the trips just loaded,
    # so it has to be rebuilt here. Skipping it would leave the onboarding screen offering
    # last month's vendor x cab type x shift combinations.
    print("\n==> refreshing vendor_fleet")
    psql(a.dsn, "-c", "SELECT refresh_vendor_fleet() AS combinations")

    manifest = os.path.join(a.dir, "_manifest.json")
    if os.path.exists(manifest):
        expected = json.load(open(manifest))
        print("\n==> expected from STAGE 2:")
        for t, cols in expected.items():
            print(f"  {t:16s} {len(cols)} columns")

    print("\n✅ load complete")
    return 0


if __name__ == "__main__":
    sys.exit(main())
