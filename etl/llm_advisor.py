"""STAGE 1 helper — the LLM's job during validation.

WHAT THE LLM DOES AND DOES NOT DO — this is a deliberate design decision, not an oversight:

  It does NOT decide pass or fail. The gate is `contracts.yml`, and it is deterministic.
  A model that gates ingestion would mean the same file could pass on Tuesday and fail on
  Wednesday, and a wrong number would then be untraceable — which is exactly the failure
  this whole pipeline exists to prevent.

  It DOES interpret the things a rule cannot judge, because the input is fuzzy and there
  is no right answer to hard-code:
      * an unfamiliar new column — what does `co2_grams` mean, is it worth adopting,
        what metric could it support?
      * a new value in a known category — is `WEATHER` a real new delay reason or a typo
        for an existing one?
      * an anomaly the rules flagged — is a 1,093 km "planned" trip plausible or junk?

  A human still approves every one of those. The model writes the proposal; the person
  clicks Adopt / Ignore / Reject in the UI.

Cost is irrelevant here: this runs once per new column ever, not once per row.
If no key is configured, or the call fails, the deterministic heuristic below runs instead
and the pipeline behaves identically — so the LLM is an enhancement, never a dependency.

Configure with:
    export ROUTEMIND_LLM_KEY=...            # Sarvam / any OpenAI-compatible key
    export ROUTEMIND_LLM_URL=https://api.sarvam.ai/v1/chat/completions
    export ROUTEMIND_LLM_MODEL=sarvam-m
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

SYSTEM = """You are a data engineer reviewing a change in an enterprise employee-transport
data feed (trips, riders, vendors, billing, safety alerts). You are given what the
validator observed. Reply in EXACTLY this form and nothing else:

MEANING: <one sentence, what this most likely represents>
RECOMMEND: <ADOPT or IGNORE>
WHY: <one sentence justification, referring to the evidence you were given>
METRIC: <a metric it could support, or NONE>

Be concrete. Do not invent facts beyond the evidence shown. If the evidence is too thin
to tell, say so and recommend IGNORE."""


class Advisor:
    def __init__(self, key: str | None = None, url: str | None = None,
                 model: str | None = None, timeout: int = 20):
        self.key = key or os.environ.get("ROUTEMIND_LLM_KEY", "")
        self.url = url or os.environ.get(
            "ROUTEMIND_LLM_URL", "https://api.sarvam.ai/v1/chat/completions")
        self.model = model or os.environ.get("ROUTEMIND_LLM_MODEL", "sarvam-m")
        self.timeout = timeout

    @property
    def available(self) -> bool:
        return bool(self.key)

    # ------------------------------------------------------------------ API
    def explain_new_column(self, source: str, column: str, profile: dict) -> str:
        evidence = (f"Table: {source}\n"
                    f"A column named '{column}' appeared that the contract does not declare.\n"
                    f"Inferred type: {profile.get('type')}\n"
                    f"Populated on {profile.get('nonNullPct')}% of rows\n"
                    f"Distinct values: {profile.get('distinct')}\n"
                    f"Sample values: {profile.get('samples')}")
        return self._ask(evidence) or heuristic_column(column, profile)

    def explain_new_values(self, source: str, column: str, values: list,
                           known: list | None = None) -> str:
        evidence = (f"Table: {source}\n"
                    f"Column '{column}' contains value(s) the contract does not list: {values}\n"
                    f"Values the contract does list: {known or 'unknown'}\n"
                    f"Is this a legitimate new category, or a typo/variant of an existing one?")
        return self._ask(evidence) or heuristic_values(column, values, known)

    def explain_anomaly(self, source: str, column: str, description: str) -> str:
        evidence = (f"Table: {source}\nColumn: {column}\n"
                    f"The validator flagged: {description}\n"
                    f"Is this plausible operational data, or a data artefact?")
        return self._ask(evidence) or (
            "MEANING: Value outside the range seen historically.\n"
            "RECOMMEND: IGNORE\n"
            f"WHY: {description} — no model configured to judge plausibility.\n"
            "METRIC: NONE\n\n(heuristic — a human decides)")

    # --------------------------------------------------------------- plumbing
    def _ask(self, user: str, max_tokens: int = 220) -> str | None:
        """Returns None on ANY failure — callers must always have a fallback."""
        if not self.available:
            return None
        body = json.dumps({
            "model": self.model,
            "temperature": 0.1,
            "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": user}],
        }).encode()
        req = urllib.request.Request(self.url, data=body, method="POST", headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.key}",
            "api-subscription-key": self.key,
        })
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                resp = json.loads(r.read().decode())
            text = resp["choices"][0]["message"]["content"].strip()
            return (text + "\n\n(assessed by model; a human decides)") if text else None
        except Exception:
            return None


# ---------------------------------------------------------------- fallbacks
# Mirrors com.routemind.schema.SchemaAdvisor#heuristic so the Python and Java paths
# propose the same thing. If you change one, change the other.
_CONCEPTS = [
    (("cost", "amount", "fare", "price", "charge"), "Looks like a monetary amount.",
     "ADOPT", "cost analysis"),
    (("km", "distance", "mileage"), "Looks like a distance measure.",
     "ADOPT", "cost per km / route efficiency"),
    (("time", "epoch", "date", "timestamp"), "Looks like a timestamp.",
     "ADOPT", "punctuality or duration"),
    (("rating", "score", "feedback", "nps"), "Looks like an experience score.",
     "ADOPT", "employee experience"),
    (("vendor", "supplier", "driver", "cab", "vehicle"),
     "Looks like a supply-side identifier or attribute.",
     "ADOPT", "vendor performance attribution"),
    (("co2", "emission", "fuel", "electric", "ev"),
     "Looks like a sustainability attribute.", "ADOPT", "emissions / EV share"),
    (("flag", "is_", "has_", "_nc", "violation", "alert"),
     "Looks like a boolean flag or compliance indicator.", "ADOPT", "compliance rate"),
]


def heuristic_column(column: str, profile: dict) -> str:
    c = column.lower()
    meaning, rec, metric = "Unrecognised field; review with the data owner.", "IGNORE", "NONE"

    # "_at" is matched as a SUFFIX only: as a substring it also catches
    # employee_attendance and similar, which are not timestamps.
    for needles, m, r, k in _CONCEPTS:
        hit = any(n in c for n in needles)
        if k == "punctuality or duration" and c.endswith("_at"):
            hit = True
        if hit:
            meaning, rec, metric = m, r, k
            break
    else:
        distinct = profile.get("distinct") or 0
        fill = profile.get("nonNullPct") or 0.0
        if 0 < distinct <= 12:
            meaning = "Small set of repeated values — likely a new category."
            rec, metric = "ADOPT", "a new breakdown dimension"
        elif fill < 5.0:
            meaning = "Almost entirely empty — probably not in use yet."

    return (f"MEANING: {meaning}\n"
            f"RECOMMEND: {rec}\n"
            f"WHY: {column} populated on {profile.get('nonNullPct')}% of rows with "
            f"{profile.get('distinct')} distinct values.\n"
            f"METRIC: {metric}\n\n"
            f"(heuristic assessment — no model configured; a human decides)")


def heuristic_values(column: str, values: list, known: list | None) -> str:
    close = []
    for v in values:
        for k in known or []:
            a, b = str(v).upper(), str(k).upper()
            if a != b and (a.startswith(b[:4]) or b.startswith(a[:4])):
                close.append(f"{v}~{k}")
    if close:
        rec = "IGNORE"
        why = ("close to existing value(s) " + ", ".join(close)
               + " — may be a variant or a typo rather than a real new category")
    else:
        rec = "ADOPT"
        why = "no close match to an existing value, so likely a genuine new category"

    return (f"MEANING: New value(s) {values} in the category column '{column}'.\n"
            f"RECOMMEND: {rec}\n"
            f"WHY: {why}; categories are stored as TEXT so they ingest either way.\n"
            f"METRIC: may add a new breakdown category.\n\n"
            f"(heuristic assessment — no model configured; a human decides)")
