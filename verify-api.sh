#!/usr/bin/env bash
# End-to-end verification against a RUNNING service + loaded Postgres.
#   ./gradlew bootRun     (in another terminal)
#   ./verify-api.sh
#
# Unit tests cover the deterministic logic; this covers the wiring: DB -> metrics ->
# rules -> narrative -> persona -> report.
set -uo pipefail

BASE="${1:-http://localhost:8080}"
FROM="${FROM:-2026-07-01}"
TO="${TO:-2026-07-31}"
DAY="${DAY:-2026-07-15}"

pass=0; fail=0
ok()   { echo "  ✅ $1"; pass=$((pass+1)); }
bad()  { echo "  ❌ $1"; fail=$((fail+1)); }
get()  { curl -s -m 30 "$BASE$1"; }
code() { curl -s -m 30 -o /dev/null -w '%{http_code}' "$BASE$1"; }

echo "== 1. service is up =="
[ "$(code /api/health/data)" = "200" ] && ok "health endpoint responds" || { bad "service not reachable at $BASE"; exit 1; }

echo "== 2. data actually loaded =="
H=$(get /api/health/data)
echo "$H" | grep -q '"status":"OK"' && ok "all five tables non-empty" || bad "a table is empty — re-run ../etl/load.sh"
echo "$H" | grep -q 'trips' && ok "trips table reported" || bad "trips missing"
echo "$H" | grep -qi 'catalyst\|vanta\|orbit\|pinnacle' && ok "business units discovered" || bad "no business units"

echo "== 3. every metric returns WITH context (the mandatory requirement) =="
M=$(get "/api/metrics?from=$FROM&to=$TO")
for f in '"target"' '"priorValue"' '"status"' '"topContributors"' '"headline"'; do
  echo "$M" | grep -q "$f" && ok "metrics carry $f" || bad "metrics missing $f"
done
for id in ota cost_per_trip no_show_rate experience safety_alerts_per_1k seat_utilisation ev_share; do
  [ "$(code "/api/metrics/$id?from=$FROM&to=$TO")" = "200" ] && ok "metric '$id' computes" || bad "metric '$id' failed"
done
[ "$(code "/api/metrics/not_a_metric?from=$FROM&to=$TO")" = "404" ] && ok "unknown metric returns 404" || bad "unknown metric should 404"

echo "== 4. OTA sanity — must match the dataset (~96.4% over May-Jul) =="
OTA=$(get "/api/metrics/ota?from=2026-05-01&to=2026-07-31" | sed -n 's/.*"value":\([0-9.]*\).*/\1/p')
echo "     value=$OTA"
awk -v v="$OTA" 'BEGIN{exit !(v>90 && v<100)}' && ok "OTA in a plausible range" || bad "OTA=$OTA looks wrong"

echo "== 5. all three personas are served =="
P=$(get /api/personas)
for p in TRANSPORT_MANAGER FACILITIES_HEAD LINE_MANAGER; do
  echo "$P" | grep -q "$p" && ok "$p registered" || bad "$p missing"
  [ "$(code "/api/insights/$p?from=$FROM&to=$TO")" = "200" ] && ok "$p insights respond" || bad "$p insights failed"
done

echo "== 6. personas get DIFFERENT metric sets (not one filtered view) =="
TM=$(get "/api/insights/TRANSPORT_MANAGER?from=$FROM&to=$TO" | tr -cd 'a-z_' | grep -o 'cost_per_trip' | head -1)
[ -z "$TM" ] && ok "transport manager is not shown cost_per_trip" || bad "persona scoping not applied"

echo "== 7. line-manager shift lens =="
S=$(get "/api/shifts?day=$DAY")
echo "$S" | grep -q 'readinessPct' && ok "shift readiness computed" || bad "shift view failed"
echo "$S" | grep -q 'note' && ok "plain-English note present" || bad "note missing"

echo "== 8. leadership report renders as HTML =="
R=$(get "/api/report/FACILITIES_HEAD?from=$FROM&to=$TO")
echo "$R" | grep -q '<html' && ok "report is HTML" || bad "report not HTML"
echo "$R" | grep -qiE 'TBD|lorem|placeholder|\{\{' && bad "report contains placeholder text" || ok "no placeholders — forwardable as-is"

echo "== 9. it triggers on its own =="
curl -s -m 30 -X POST "$BASE/api/scan?asOf=$TO" > /dev/null && ok "manual scan accepted" || bad "scan failed"
get /api/scan/status | grep -q 'cooldownDays' && ok "cooldown/dedup configured" || bad "scan status missing"

echo "== 10. predictive layer =="
[ "$(code "/api/live/risk?day=$DAY")" = "200" ] && ok "pre-trip risk computes" || bad "live risk failed"
L=$(get /api/live/status)
echo "$L" | grep -q 'HISTORICAL_PATTERN\|LIVE_GPS' && ok "prediction mode reported: $(echo "$L" | grep -o 'HISTORICAL_PATTERN\|LIVE_GPS' | head -1)" || bad "no prediction mode"

echo "== 11. onboarding can identify an unfamiliar export =="
O=$(curl -s -m 30 -X POST "$BASE/api/onboarding/propose" -H 'Content-Type: application/json' \
  -d '{"sourceId":"other-vendor","columns":[{"name":"ride_id","samples":["1"]},{"name":"supplier","samples":["Acme"]},{"name":"zz_unknown","samples":["x"]}]}')
echo "$O" | grep -q 'tripId' && ok "recognised ride_id -> tripId" || bad "alias matching failed"
echo "$O" | grep -q 'capabilities' && ok "capability flags returned" || bad "capabilities missing"
echo "$O" | grep -q 'zz_unknown' && ok "unknown column surfaced for review" || bad "unknown column swallowed"

echo "== 12. config is declarative =="
get /api/config | grep -q 'targets' && ok "targets exposed from config" || bad "config endpoint failed"

echo
echo "-------------------------------------------"
echo " passed: $pass   failed: $fail"
[ "$fail" -eq 0 ] && echo " ALL CHECKS PASSED" || echo " SOME CHECKS FAILED"
echo "-------------------------------------------"
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
