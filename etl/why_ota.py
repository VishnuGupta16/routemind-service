"""Why is OTA down? — the shift-share decomposition, in plain Python against the CSVs.

Same arithmetic as OtaRootCauseService in the Java service, kept here for two reasons: it
runs the demo's headline question with nothing but the normalised files (no Postgres, no
Spring), and it is a second, independent implementation the Java numbers can be checked
against. If these two ever disagree, one of them has a bug.

    python3 why_ota.py                       # June vs May, the real drop
    python3 why_ota.py --from 2026-06-01 --to 2026-06-30
    python3 why_ota.py --bu pinnacle-Slc

The method: attribute the change in the ON-TIME RATE to each value of a dimension as

    contribution(g) = -100 x (lateRate_now(g) - lateRate_prev(g)) x volumeShare_now(g)

A negative contribution means that group pulled OTA down. Summed across a dimension the
contributions reconstruct the total change, so "morning pickups cost 1.4 of the 2.0 points"
is checkable arithmetic, not an impression.
"""
from __future__ import annotations

import argparse
import os
from datetime import date, timedelta

import pandas as pd

WINDOW = 10          # default on-time window, minutes (per-vendor SLAs override in the service)
RENTAL = "SPOT_2.0"
MIN_GROUP = 200


def load(d: str) -> pd.DataFrame:
    t = pd.read_csv(os.path.join(d, "trips.csv"), low_memory=False,
                    usecols=["business_unit", "office", "vendor", "product_type",
                             "shift_band", "trip_direction", "delay_reason",
                             "delay_minutes", "trip_date"])
    t = t[t.product_type != RENTAL].copy()
    t["trip_date"] = pd.to_datetime(t.trip_date).dt.date
    t["late"] = t.delay_minutes > WINDOW
    return t


def ota(df: pd.DataFrame) -> float:
    return round(100 * (1 - df.late.mean()), 2) if len(df) else float("nan")


def decompose(now: pd.DataFrame, prev: pd.DataFrame, dim: str) -> pd.DataFrame:
    n = now.groupby(dim).late.agg(trips="size", late="sum")
    p = prev.groupby(dim).late.agg(trips_prev="size", late_prev="sum")
    j = n.join(p, how="left")
    total_now = n.trips.sum()

    j["lr_now"] = j["late"] / j["trips"]
    j["lr_prev"] = (j["late_prev"] / j["trips_prev"]).fillna(j["lr_now"])
    j["ota_now"] = (100 * (1 - j["lr_now"])).round(1)
    j["ota_prev"] = (100 * (1 - j["lr_prev"])).round(1)
    j["late_added"] = (j["late"] - (j["lr_prev"] * j["trips"]).round()).astype(int)
    j["contribution"] = (-100 * (j["lr_now"] - j["lr_prev"])
                         * (j["trips"] / total_now)).round(2)
    j = j[j["trips"] >= MIN_GROUP]
    return j.sort_values("contribution")[
        ["ota_prev", "ota_now", "trips", "late_added", "contribution"]]


def reason_shift(now: pd.DataFrame, prev: pd.DataFrame) -> pd.DataFrame:
    def mix(df):
        late = df[df.late & (df.delay_reason != "NODELAY")]
        return late.delay_reason.value_counts(normalize=True) * 100
    a, b = mix(prev), mix(now)
    out = pd.DataFrame({"share_prev": a, "share_now": b}).fillna(0).round(1)
    out["change_pts"] = (out.share_now - out.share_prev).round(1)
    out["controllable"] = out.index.to_series().str.upper().eq("DRIVER")
    return out.sort_values("share_now", ascending=False)


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(here, "normalized"))
    ap.add_argument("--from", dest="frm", default="2026-06-01")
    ap.add_argument("--to", default="2026-06-30")
    ap.add_argument("--bu", default=None)
    a = ap.parse_args()

    t = load(a.dir)
    if a.bu:
        t = t[t.business_unit == a.bu]

    frm = date.fromisoformat(a.frm)
    to = date.fromisoformat(a.to)
    days = (to - frm).days + 1
    prior_to = frm - timedelta(days=1)
    prior_from = prior_to - timedelta(days=days - 1)

    now = t[(t.trip_date >= frm) & (t.trip_date <= to)]
    prev = t[(t.trip_date >= prior_from) & (t.trip_date <= prior_to)]

    o_now, o_prev = ota(now), ota(prev)
    change = round(o_now - o_prev, 2)

    print("=" * 70)
    print(f"WHY IS OTA {'DOWN' if change < 0 else 'UP' if change > 0 else 'FLAT'}?"
          + (f"   [{a.bu}]" if a.bu else ""))
    print("=" * 70)
    print(f"  {a.frm}..{a.to}   OTA {o_now}%   ({len(now):,} trips)")
    print(f"  prior {prior_from}..{prior_to}   OTA {o_prev}%   ({len(prev):,} trips)")
    print(f"  change: {change:+.2f} points\n")

    for dim, title in [("trip_direction", "By direction"),
                       ("shift_band", "By shift band"),
                       ("product_type", "By cab type"),
                       ("office", "By office"),
                       ("vendor", "By vendor")]:
        print(f"-- {title} (contribution to the change, points) --")
        d = decompose(now, prev, dim)
        for k, r in d.head(4).iterrows():
            print(f"   {str(k)[:26]:26} {r.contribution:+6.2f}   "
                  f"OTA {r.ota_prev:5.1f}->{r.ota_now:5.1f}   {int(r.trips):>7,} trips")
        print(f"   [contributions across all groups sum to {d.contribution.sum():+.2f} "
              f"of the {change:+.2f} total; residual is volume-mix]\n")

    print("-- Cause mix of late trips (is it becoming the vendor's fault?) --")
    rs = reason_shift(now, prev)
    for reason, r in rs.iterrows():
        tag = "  <- vendor-controllable (A4 unconfirmed)" if r.controllable else ""
        print(f"   {reason:12} {r.share_prev:5.1f}% -> {r.share_now:5.1f}%  "
              f"({r.change_pts:+.1f} pts){tag}")


if __name__ == "__main__":
    main()
