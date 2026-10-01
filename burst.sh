#!/usr/bin/env bash
# ============================================================================
# On-sale stampede load test for the seat reservation service.
#
# Reproduces the assignment's correctness scenario against a live BASE_URL:
#   1. Creates a fresh show with N seats.
#   2. HOT-SEAT STORM: many concurrent users all try the SAME few seats at once.
#      Exactly one must win each hot seat; everyone else gets a clean 409.
#   3. GENERAL STAMPEDE: many users grab random seats concurrently.
#   4. IDEMPOTENCY: fires the same key many times; must create exactly one.
#   5. PER-USER LIMIT: one user fires more parallel reserves than the limit.
#   6. Prints the outcome distribution (201 / 409-by-reason / 5xx) and the final
#      reconciliation (available + held + confirmed == total_seats).
#
# Usage:   ./burst.sh <BASE_URL> [TOTAL_SEATS] [HOT_SEAT_USERS] [STAMPEDE_USERS]
# Example: ./burst.sh https://seat-reservation.onrender.com
#
# Requires: bash, curl, jq.
# ============================================================================
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
TOTAL_SEATS="${2:-200}"
HOT_SEAT_USERS="${3:-500}"
STAMPEDE_USERS="${4:-400}"
BASE_URL="${BASE_URL%/}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v jq >/dev/null 2>&1 || { echo "jq is required"; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }

echo "=============================================================="
echo " Seat reservation burst  ->  $BASE_URL"
echo " total_seats=$TOTAL_SEATS  hot_seat_users=$HOT_SEAT_USERS  stampede_users=$STAMPEDE_USERS"
echo "=============================================================="

# ---- 0. Health gate (survive cold start) --------------------------------
echo -n "Waiting for readiness"
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health/readiness" || true)"
  if [ "$code" = "200" ]; then echo " ... ready"; break; fi
  echo -n "."
  sleep 2
  if [ "$i" = "60" ]; then echo " TIMEOUT waiting for readiness"; exit 1; fi
done

# ---- 1. Create a fresh show --------------------------------------------
# Seat labels S1..S{TOTAL_SEATS}
SEATS_JSON="$(seq 1 "$TOTAL_SEATS" | sed 's/^/"S/; s/$/"/' | paste -sd, -)"
CREATE_BODY="{\"name\":\"burst-$(date +%s)\",\"seats\":[$SEATS_JSON],\"price_paise\":25000}"
SHOW="$(curl -s -X POST "$BASE_URL/shows" -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer admin-token' -d "$CREATE_BODY")"
SHOW_ID="$(echo "$SHOW" | jq -r '.id')"
if [ -z "$SHOW_ID" ] || [ "$SHOW_ID" = "null" ]; then
  echo "Failed to create show. Response: $SHOW"; exit 1
fi
echo "Created show: $SHOW_ID"

# Helper: fire one reserve, append HTTP code + decline reason to a results file.
# args: user, seats_json, idempotency_key, results_file
fire_reserve() {
  local user="$1" seats="$2" key="$3" out="$4"
  local body resp code reason
  body="{\"seats\":$seats,\"idempotency_key\":\"$key\"}"
  resp="$(curl -s -w $'\n%{http_code}' -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${user}-token" \
    -H "Idempotency-Key: $key" \
    -d "$body")"
  code="$(echo "$resp" | tail -n1)"
  reason="$(echo "$resp" | sed '$d' | jq -r '.code // "ok"' 2>/dev/null || echo "parse_err")"
  echo "$code $reason" >> "$out"
}
export -f fire_reserve
export BASE_URL SHOW_ID

# ---- 2. HOT-SEAT STORM: everyone fights for S1 (and S2, S3) ------------
echo
echo ">>> Hot-seat storm: $HOT_SEAT_USERS users all target seat S1 at once"
HOT_OUT="$TMP/hot.txt"; : > "$HOT_OUT"
for u in $(seq 1 "$HOT_SEAT_USERS"); do
  fire_reserve "hotuser$u" '["S1"]' "hot-$u" "$HOT_OUT" &
  # keep a lid on local fd/process pressure
  if (( u % 100 == 0 )); then wait; fi
done
wait

HOT_201=$(grep -c '^201 ' "$HOT_OUT" || true)
HOT_409=$(grep -c '^409 ' "$HOT_OUT" || true)
HOT_5XX=$(grep -cE '^5[0-9][0-9] ' "$HOT_OUT" || true)
echo "   S1 winners (201): $HOT_201   losers (409): $HOT_409   5xx: $HOT_5XX"

# ---- 3. GENERAL STAMPEDE: random seats ---------------------------------
echo
echo ">>> General stampede: $STAMPEDE_USERS users grab random seats"
STAMP_OUT="$TMP/stamp.txt"; : > "$STAMP_OUT"
for u in $(seq 1 "$STAMPEDE_USERS"); do
  s=$(( (RANDOM % TOTAL_SEATS) + 1 ))
  fire_reserve "user$u" "[\"S$s\"]" "stamp-$u-$s" "$STAMP_OUT" &
  if (( u % 100 == 0 )); then wait; fi
done
wait

# ---- 4. IDEMPOTENCY: same key fired many times -------------------------
echo
echo ">>> Idempotency: 50 parallel retries of the SAME key for seat S2"
IDEM_OUT="$TMP/idem.txt"; : > "$IDEM_OUT"
for u in $(seq 1 50); do
  fire_reserve "idemuser" '["S2"]' "fixed-idem-key" "$IDEM_OUT" &
done
wait
IDEM_201=$(grep -c '^201 ' "$IDEM_OUT" || true)
echo "   201 responses for the shared key (idempotent): $IDEM_201 (seat S2 held once)"

# different body, same key -> expect 409 idempotency_conflict
CONFLICT="$(curl -s -w $'\n%{http_code}' -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer idemuser-token' \
  -H 'Idempotency-Key: fixed-idem-key' -d '{"seats":["S3"]}')"
echo "   same-key-different-body -> HTTP $(echo "$CONFLICT" | tail -n1), code=$(echo "$CONFLICT" | sed '$d' | jq -r '.code // "n/a"')"

# ---- 5. PER-USER LIMIT: one user fires 10 parallel single-seat holds ---
echo
echo ">>> Per-user limit: 'limituser' fires 10 parallel reserves (limit=4)"
LIMIT_OUT="$TMP/limit.txt"; : > "$LIMIT_OUT"
for s in $(seq 50 59); do
  fire_reserve "limituser" "[\"S$s\"]" "limit-$s" "$LIMIT_OUT" &
done
wait
LIMIT_201=$(grep -c '^201 ' "$LIMIT_OUT" || true)
echo "   confirmed for limituser: $LIMIT_201 (must be <= 4)"

# ---- 6. Aggregate outcome distribution ---------------------------------
cat "$HOT_OUT" "$STAMP_OUT" "$IDEM_OUT" "$LIMIT_OUT" > "$TMP/all.txt"
echo
echo "=============================================================="
echo " OUTCOME DISTRIBUTION (all requests)"
echo "=============================================================="
TOTAL=$(wc -l < "$TMP/all.txt" | tr -d ' ')
C201=$(grep -c '^201 ' "$TMP/all.txt" || true)
C409=$(grep -c '^409 ' "$TMP/all.txt" || true)
C4XX_OTHER=$(grep -cE '^4[0-9][0-9] ' "$TMP/all.txt" || true)
C5XX=$(grep -cE '^5[0-9][0-9] ' "$TMP/all.txt" || true)
echo "   total requests : $TOTAL"
echo "   201 confirmed  : $C201"
echo "   409 declined   : $C409"
echo "   other 4xx      : $(( C4XX_OTHER - C409 ))"
echo "   5xx (MUST BE 0): $C5XX"
echo
echo "   declines by reason:"
grep '^409 ' "$TMP/all.txt" | awk '{print $2}' | sort | uniq -c | sed 's/^/     /'

# ---- 7. Reconciliation invariant ---------------------------------------
echo
echo "=============================================================="
echo " RECONCILIATION  (available + held + confirmed == total_seats)"
echo "=============================================================="
STATE="$(curl -s "$BASE_URL/shows/$SHOW_ID")"
echo "$STATE" | jq '{available: .counts.available, held: .counts.held, confirmed: .counts.confirmed, total: .counts.total_seats}'
AVAIL=$(echo "$STATE" | jq '.counts.available')
HELD=$(echo "$STATE" | jq '.counts.held')
CONF=$(echo "$STATE" | jq '.counts.confirmed')
TOTS=$(echo "$STATE" | jq '.counts.total_seats')
SUM=$(( AVAIL + HELD + CONF ))
echo "   sum=$SUM  total=$TOTS"

echo
PASS=true
[ "$C5XX" -eq 0 ] || { echo "FAIL: observed $C5XX server errors (5xx)"; PASS=false; }
[ "$SUM" -eq "$TOTS" ] || { echo "FAIL: reconciliation off ($SUM != $TOTS)"; PASS=false; }
[ "$HOT_201" -eq 1 ] || { echo "FAIL: hot seat S1 had $HOT_201 winners (expected exactly 1)"; PASS=false; }
[ "$LIMIT_201" -le 4 ] || { echo "FAIL: per-user limit breached ($LIMIT_201 > 4)"; PASS=false; }
[ "$IDEM_201" -ge 1 ] || { echo "FAIL: idempotent key produced no confirmation"; PASS=false; }

if $PASS; then
  echo "=============================================================="
  echo " ALL CORRECTNESS CHECKS PASSED"
  echo "=============================================================="
  exit 0
else
  echo "=============================================================="
  echo " CORRECTNESS CHECKS FAILED (see above)"
  echo "=============================================================="
  exit 1
fi
