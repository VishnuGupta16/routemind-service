"""The data-ingestion pipeline. Three stages, in this order, always.

    raw monthly drop
          │
          ▼
    ┌───────────────────────────────────────────────────────────────────────┐
    │ STAGE 1  VALIDATE            validate.py + llm_advisor.py             │
    │   Deterministic contract check against contracts.yml decides PASS or  │
    │   FAIL. An LLM then EXPLAINS the findings a rule cannot judge — a new │
    │   column, an unknown category value, an implausible number — and      │
    │   proposes an action. The model never changes the verdict.            │
    └───────────────────────────────────────────────────────────────────────┘
          │  FAIL ⇒ stop. Nothing reaches Postgres.
          ▼
    ┌───────────────────────────────────────────────────────────────────────┐
    │ STAGE 2  CLEAN               clean.py, driven by rules.yml            │
    │   Every cleaning rule is declared in rules.yml with a `why:` citing   │
    │   the organisers' data dictionary or a STAGE 1 finding. clean.py is   │
    │   only an interpreter, so the rules can be audited without reading    │
    │   any code.                                                           │
    └───────────────────────────────────────────────────────────────────────┘
          │
          ▼
    ┌───────────────────────────────────────────────────────────────────────┐
    │ STAGE 3  INJECT              load.py                                  │
    │   Loads each file into its table. Before loading it reconciles the    │
    │   CSV's columns with the table's: anything new is added NULLABLE with │
    │   no backfill, so historical rows read NULL = "not collected then".   │
    │   Nothing is ever dropped or retyped automatically. New values in an  │
    │   existing category need no migration at all.                         │
    └───────────────────────────────────────────────────────────────────────┘

This pipeline is deliberately SEPARATE from the RouteMind service. It runs on its own,
on a schedule or by hand, and talks to the service over one optional HTTP call — pushing
the schema changes STAGE 1 found so a human can decide on them in the UI. The service
never blocks the pipeline, and the pipeline never blocks the service.

    # everything, end to end
    python pipeline.py --src "<raw dir>" --dsn postgresql://routemind:pass@localhost:5432/routemind

    python pipeline.py --src "<dir>"                  # stages 1-2, stop before the DB
    python pipeline.py --src "<dir>" --stage 1        # the gate alone
    python pipeline.py --src "<dir>" --strict         # warnings also stop the run
    python pipeline.py --src "<dir>" --dsn ... --force        # ingest despite failures
    python pipeline.py --src "<dir>" --dsn ... --truncate     # full reload, not append
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

STAGES = {1: "VALIDATE  (deterministic gate + LLM interpretation)",
          2: "CLEAN     (rules.yml, traceable to the data dictionary)",
          3: "INJECT    (backward-compatible load into Postgres)"}


def banner(n: int) -> None:
    print()
    print("=" * 74)
    print(f"  STAGE {n}/3 — {STAGES[n]}")
    print("=" * 74)


def run(cmd: list[str]) -> int:
    print("  $ " + " ".join(str(c) for c in cmd))
    return subprocess.call(cmd)


def push_changes(report_path: str, service: str) -> int:
    """Send new columns / new category values to the service for a human decision.

    Best-effort by design: if the service is down the pipeline still completes, and the
    report is on disk to be posted later. Ingestion must not depend on the UI being up.
    """
    import json
    import urllib.request

    if not os.path.exists(report_path):
        return 0
    try:
        report = json.load(open(report_path))
    except Exception:
        return 0

    interesting = [i for i in report.get("issues", [])
                   if i.get("code") in ("UNEXPECTED_COLUMN", "NEW_ENUM_VALUE")]
    if not interesting:
        return 0

    try:
        req = urllib.request.Request(
            f"{service.rstrip('/')}/api/schema/report",
            data=json.dumps(report).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as r:
            body = json.loads(r.read().decode())
        print(f"  → pushed {body.get('ingested', 0)} schema change(s) to {service}")
        return int(body.get("ingested", 0))
    except Exception as e:
        print(f"  → could not reach {service} ({e.__class__.__name__}); "
              f"{len(interesting)} change(s) are in {os.path.basename(report_path)}")
        return 0


def pull_decisions(service: str, dest: str) -> None:
    """Fetch the decisions a human made in the UI, so STAGE 2 honours them.

    Without this, a column the operator adopted last month would be dropped again this
    month — the decision has to travel back to the pipeline for adoption to mean anything.
    """
    import json
    import urllib.request
    try:
        with urllib.request.urlopen(f"{service.rstrip('/')}/api/schema/decisions",
                                    timeout=10) as r:
            data = json.loads(r.read().decode())
        json.dump(data, open(dest, "w"), indent=2)
        n = sum(len(v) for v in data.values()) if isinstance(data, dict) else 0
        print(f"  → pulled {n} schema decision(s) from {service}")
    except Exception:
        print(f"  → no decisions pulled (service unreachable); using {os.path.basename(dest)}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="directory of raw monthly files")
    ap.add_argument("--out", default=os.path.join(HERE, "normalized"))
    ap.add_argument("--dsn", help="Postgres DSN; omit to stop after STAGE 2")
    ap.add_argument("--stage", type=int, choices=[1, 2, 3],
                    help="run only up to this stage")
    ap.add_argument("--strict", action="store_true", help="warnings stop the pipeline too")
    ap.add_argument("--force", action="store_true", help="ingest even if STAGE 1 fails")
    ap.add_argument("--truncate", action="store_true", help="full reload instead of append")
    ap.add_argument("--no-explain", action="store_true", help="skip the LLM step in STAGE 1")
    ap.add_argument("--report", default=os.path.join(HERE, "validation-report.json"))
    ap.add_argument("--decisions", default=os.path.join(HERE, "schema-decisions.json"))
    ap.add_argument("--service", default="http://localhost:8080",
                    help="RouteMind service — schema changes are pushed here for review")
    ap.add_argument("--no-sync", action="store_true",
                    help="don't exchange schema changes/decisions with the service")
    a = ap.parse_args()

    last = a.stage or (3 if a.dsn else 2)
    t0 = time.time()

    # ---------------------------------------------------------- 1. VALIDATE
    banner(1)
    if not a.no_sync:
        pull_decisions(a.service, a.decisions)

    cmd = [sys.executable, os.path.join(HERE, "validate.py"),
           "--src", a.src, "--json", a.report, "--decisions", a.decisions]
    if a.strict:
        cmd.append("--strict")
    if a.no_explain:
        cmd.append("--no-explain")
    rc = run(cmd)

    pushed = 0 if a.no_sync else push_changes(a.report, a.service)

    if rc != 0 and not a.force:
        print("\n  ⛔ PIPELINE STOPPED at STAGE 1. Nothing was written to Postgres.")
        if pushed:
            print(f"     {pushed} schema change(s) are waiting for you:")
            print(f"     {a.service}/  →  Schema tab  →  Adopt / Ignore / Reject")
        else:
            print("     Fix the drift, or update contracts.yml if the change is legitimate.")
        print("     Use --force only if you accept the risk.")
        return 1
    if rc != 0:
        print("\n  ⚠️  STAGE 1 failed but --force was given — continuing anyway.")
    else:
        print("\n  ✅ contract check passed — safe to clean.")
        if pushed:
            print(f"     ({pushed} change(s) queued in the UI for review)")

    if last == 1:
        print(f"\n  stopping after STAGE 1. ({time.time()-t0:.1f}s)")
        return 0

    # ------------------------------------------------------------- 2. CLEAN
    banner(2)
    os.makedirs(a.out, exist_ok=True)
    if run([sys.executable, os.path.join(HERE, "clean.py"),
            "--src", a.src, "--out", a.out, "--decisions", a.decisions]) != 0:
        print("\n  ⛔ STAGE 2 failed. Nothing was written to Postgres.")
        return 1
    print(f"\n  ✅ cleaned files in {a.out}")

    if last == 2 or not a.dsn:
        if not a.dsn:
            print(f"     no --dsn given, so STAGE 3 was skipped. To load them:")
            print(f"     python load.py --dsn <dsn> --dir {a.out}")
        print(f"\n  done in {time.time()-t0:.1f}s")
        return 0

    # ------------------------------------------------------------ 3. INJECT
    banner(3)
    cmd = [sys.executable, os.path.join(HERE, "load.py"), "--dsn", a.dsn, "--dir", a.out]
    if a.truncate:
        cmd.append("--truncate")
    if run(cmd) != 0:
        print("\n  ⛔ STAGE 3 failed.")
        return 1

    print(f"\n  ✅ pipeline complete in {time.time()-t0:.1f}s")
    print(f"     validation report: {a.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
