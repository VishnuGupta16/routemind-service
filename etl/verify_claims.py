#!/usr/bin/env python3
"""Re-derive every number that appears in the pitch deck, the demo script and the docs.

Nothing in the deck should be quoted from memory or from an earlier run. This recomputes
each claim from the normalised CSVs and prints it, so a number can be checked in seconds
rather than trusted.

    python3 verify_claims.py [--dir normalized]
"""
from __future__ import annotations

import argparse
import os

import pandas as pd

OTA_WINDOW = 10          # default SLA window, minutes; per-vendor policies override in-app
RENTAL = "SPOT_2.0"      # rental cab — no pickup commitment, so no on-time target


def money(x: float) -> str:
    return f"Rs {x/1e6:,.1f}M"


def pct(x: float) -> str:
    return f"{x:.2f}%"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "normalized"))
    a = ap.parse_args()
    d = a.dir

    trips = pd.read_csv(os.path.join(d, "trips.csv"), low_memory=False)
    emps = pd.read_csv(os.path.join(d, "trip_employees.csv"), low_memory=False)
    bill = pd.read_csv(os.path.join(d, "billing.csv"), low_memory=False)
    alerts = pd.read_csv(os.path.join(d, "alerts.csv"), low_memory=False)
    fb = pd.read_csv(os.path.join(d, "feedback.csv"), low_memory=False)

    print("=" * 74)
    print("SCALE")
    print("=" * 74)
    print(f"  trips                {len(trips):,}")
    print(f"  employee legs        {len(emps):,}")
    print(f"  distinct riders      {emps.stwid.nunique():,}")
    print(f"  business units       {trips.business_unit.nunique()}")
    print(f"  offices              {trips.office.nunique()}")
    print(f"  vendors (trips)      {trips.vendor.nunique()}")
    print(f"  vehicles             {trips.actual_cab_reg.nunique():,}")
    print(f"  date range           {trips.trip_date.min()} .. {trips.trip_date.max()}")
    print(f"  alerts               {len(alerts):,}")
    print(f"  feedback rows        {len(fb):,}")

    print()
    print("=" * 74)
    print(f"PUNCTUALITY  (on time = delay_minutes <= {OTA_WINDOW})")
    print("=" * 74)
    t = trips.copy()
    t["late"] = t.delay_minutes > OTA_WINDOW
    sched = t[t.product_type != RENTAL]

    print(f"  OTA, all trips           {pct(100 * (1 - t.late.mean()))}  (n={len(t):,})")
    print(f"  OTA, rentals excluded    {pct(100 * (1 - sched.late.mean()))}  (n={len(sched):,})")
    print(f"  rental trips excluded    {len(t) - len(sched):,}")
    print(f"  late trips               {int(t.late.sum()):,}")

    print("\n  by direction:")
    for k, g in sched.groupby("trip_direction"):
        print(f"    {k:<10} {pct(100*(1-g.late.mean())):>8}   n={len(g):,}")

    print("\n  by cab type (schedulable only):")
    for k, g in sched.groupby("product_type"):
        print(f"    {k:<10} {pct(100*(1-g.late.mean())):>8}   n={len(g):,}")
    print(f"    [rental {RENTAL} excluded from OTA by policy: n={len(t)-len(sched):,}]")

    print("\n  worst 5 shifts (>=1000 trips):")
    g = sched.groupby("shift_type").late.agg(["mean", "size"])
    g = g[g["size"] >= 1000].sort_values("mean", ascending=False).head(5)
    for k, r in g.iterrows():
        print(f"    {str(k):<12} {pct(100*(1-r['mean'])):>8}   n={int(r['size']):,}")

    print("\n  by month:")
    for k, g2 in sched.groupby(pd.to_datetime(sched.trip_date).dt.to_period("M")):
        print(f"    {k}      {pct(100*(1-g2.late.mean())):>8}   n={len(g2):,}")

    print("\n  recorded cause of the late trips:")
    late = t[t.late]
    for k, v in late.delay_reason.value_counts(normalize=True).items():
        print(f"    {str(k):<12} {pct(100*v):>8}   n={int(late.delay_reason.value_counts()[k]):,}")

    print()
    print("=" * 74)
    print("UTILISATION")
    print("=" * 74)
    cap = trips[trips.actual_cab_capacity > 0]
    util = 100 * cap.actual_employee_cnt.sum() / cap.actual_cab_capacity.sum()
    empty = int((cap.actual_cab_capacity - cap.actual_employee_cnt).clip(lower=0).sum())
    single = 100 * (trips.actual_employee_cnt == 1).mean()
    print(f"  seat utilisation         {pct(util)}")
    print(f"  empty seat-trips         {empty:,}")
    print(f"  trips with 1 rider       {pct(single)}  ({int((trips.actual_employee_cnt==1).sum()):,} trips)")
    for k, g in cap.groupby("product_type"):
        u = 100 * g.actual_employee_cnt.sum() / g.actual_cab_capacity.sum()
        print(f"    {k:<10} {pct(u):>8}")

    print()
    print("=" * 74)
    print("COST")
    print("=" * 74)
    # Two definitions differ here and both are defensible, so BOTH are printed. Quoting one
    # while meaning the other is exactly how a deck ends up with a number nobody can
    # reproduce. "net" = penalties subtracted; "gross" = billed amounts only.
    oh = bill[bill.is_overhead.astype(str).str.lower().isin(["true", "1"])]
    real = bill[~bill.index.isin(oh.index)]
    pen_all = bill[bill.trip_cost < 0]
    pen_trip = real[real.trip_cost < 0]

    print(f"  billing lines            {len(bill):,}")
    print(f"  overhead lines           {len(oh):,}   total {money(oh.trip_cost.sum())}")
    print(f"  penalty lines, ALL       {len(pen_all):,}   {money(pen_all.trip_cost.sum())}"
          f"   <- the headline figure")
    print(f"    of which on trip lines {len(pen_trip):,}   {money(pen_trip.trip_cost.sum())}")
    print(f"    of which on overhead   {len(pen_all)-len(pen_trip):,}"
          f"   {money(pen_all.trip_cost.sum()-pen_trip.trip_cost.sum())}")
    print(f"  largest single penalty   Rs {pen_all.trip_cost.min():,.0f}")
    print(f"  vendors penalised        {pen_all.vendor.nunique()} of {bill.vendor.nunique()}")
    print(f"  contracts penalised      {sorted(pen_all.contract.dropna().unique())}")

    print(f"  spend, net of penalties  {money(bill.trip_cost.sum())}   <- the headline figure")
    print(f"  spend, gross             {money(bill[bill.trip_cost>0].trip_cost.sum())}")
    print(f"  mean per trip line, net  Rs {real.trip_cost.mean():,.0f}   <- the headline figure")
    print(f"  mean per trip line, gross Rs {real[real.trip_cost>0].trip_cost.mean():,.0f}")
    print(f"  median (billed lines)    Rs {real[real.trip_cost>0].trip_cost.median():,.0f}")

    km = real[real.total_trip_km > 0]
    kmp = km[km.trip_cost > 0]
    print(f"  cost per km, net         Rs {km.trip_cost.sum()/km.total_trip_km.sum():,.1f}"
          f"   <- the headline figure")
    print(f"  cost per km, gross       Rs {kmp.trip_cost.sum()/kmp.total_trip_km.sum():,.1f}")
    print(f"  lines with km = 0        {pct(100*(real.total_trip_km==0).mean())}"
          f"  ({int((real.total_trip_km==0).sum()):,} of {len(real):,})")
    top = (bill.groupby("vendor").trip_cost.sum() / 1e6).sort_values(ascending=False)
    print(f"  largest vendor           {top.index[0]} at Rs {top.iloc[0]:,.1f}M"
          f"  ({int(real.vendor.value_counts().iloc[0]):,} trip lines)")

    print()
    print("=" * 74)
    print("SAFETY")
    print("=" * 74)
    print(f"  alerts                   {len(alerts):,}")
    print(f"  per 1,000 trips          {1000*len(alerts)/len(trips):.1f}")
    print(f"  severity null            {pct(100*alerts.severity.isna().mean())}")
    ack = pd.to_datetime(alerts.acknowledge_time, errors="coerce")
    st = pd.to_datetime(alerts.start_time, errors="coerce")
    alerts = alerts.assign(resp=(ack - st).dt.total_seconds() / 60)
    print(f"  acknowledged             {pct(100*ack.notna().mean())}")
    print(f"  median response (min)    {alerts.resp.median():.0f}")
    print("\n  by type:")
    vc = alerts.event_type.value_counts()
    for k, v in vc.items():
        med = alerts.loc[alerts.event_type == k, "resp"].median()
        print(f"    {k:<38} {pct(100*v/len(alerts)):>7}  n={v:,}  median {med:,.0f} min")

    print()
    print("=" * 74)
    print("PEOPLE")
    print("=" * 74)
    ns = emps.is_no_show.astype(str).str.lower().isin(["true", "1"])
    print(f"  no-show rate             {pct(100*ns.mean())}")
    for k, g in emps.assign(ns=ns).groupby("gender"):
        print(f"    {k:<8} {pct(100*g.ns.mean()):>8}   n={len(g):,}")
    print(f"  boarded                  {pct(100*emps.boarding_status.eq('Boarded').mean())}")
    print("\n  not-boarding reason:")
    for k, v in emps.not_boarding_reason.value_counts(normalize=True).items():
        print(f"    {str(k):<32} {pct(100*v):>7}")

    print()
    print("=" * 74)
    print("SUSTAINABILITY & EXPERIENCE")
    print("=" * 74)
    for k, v in trips.fuel_type.value_counts(normalize=True).items():
        print(f"  fuel {str(k):<10} {pct(100*v):>8}")
    cols = [c for c in ("route_rating", "driver_rating", "cab_rating",
                        "safety_rating", "marshal_rating") if c in fb.columns]
    overall = fb[cols].mean(axis=1)
    print(f"  mean rating (all cols)   {overall.mean():.2f}   n={int(overall.notna().sum()):,}")
    print(f"  one-star share           {pct(100*(overall <= 1.0).mean())}")

    print()
    print("=" * 74)
    print("DATA-QUALITY FLAGS (things a human should still confirm)")
    print("=" * 74)
    print(f"  delay_minutes max        {trips.delay_minutes.max():,.0f} min")
    print(f"  delays > 240 min         {int((trips.delay_minutes>240).sum()):,}")
    print(f"  planned_km max           {trips.planned_km.max():,.0f} km")
    ids = trips.trip_id.value_counts()
    print(f"  trip_ids seen more than once  {int((ids>1).sum()):,}")


if __name__ == "__main__":
    main()
