#!/usr/bin/env bash
# End-to-end check against running services (local run or docker compose).
# Needs curl and jq. Expects the payment failure rate to be 0 so the happy path is deterministic:
#   PAYMENT_FAILURE_RATE=0 docker compose up -d --build
set -euo pipefail

BOOKING_URL="${BOOKING_URL:-http://localhost:8080}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8081}"
RUN_ID="$(date +%s)-$RANDOM"

pass() { printf '  \033[32m✔\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✘\033[0m %s\n' "$1"; exit 1; }

wait_for_health() {
  local name=$1 url=$2
  for _ in $(seq 1 90); do
    if [ "$(curl -fs "$url/actuator/health" | jq -r .status 2>/dev/null)" = "UP" ]; then
      pass "$name is up"
      return
    fi
    sleep 2
  done
  fail "$name did not become healthy at $url"
}

# post_booking <showId> <userId> <idempotencyKey> <seatId...>  -> prints "<httpStatus> <body>"
post_booking() {
  local show=$1 user=$2 key=$3; shift 3
  local seats; seats=$(printf '%s\n' "$@" | jq -s -c .)
  curl -s -o /tmp/smoke-body -w '%{http_code}' -X POST "$BOOKING_URL/api/shows/$show/bookings" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d "{\"userId\":\"$user\",\"seatIds\":$seats}"
  printf ' %s\n' "$(cat /tmp/smoke-body)"
}

wait_for_status() {
  local booking=$1 expected=$2
  for _ in $(seq 1 60); do
    local status; status=$(curl -fs "$BOOKING_URL/api/bookings/$booking" | jq -r .status)
    [ "$status" = "$expected" ] && return 0
    sleep 0.5
  done
  return 1
}

echo "Smoke test ($RUN_ID)"
wait_for_health booking-service "$BOOKING_URL"
wait_for_health payment-service "$PAYMENT_URL"

show=$(curl -fs -X POST "$BOOKING_URL/api/shows" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke Test","venue":"CI","startsAt":"2099-01-01T19:30:00Z","rows":2,"seatsPerRow":5,"priceCents":25000}')
show_id=$(jq -r .id <<<"$show")
read -r seat1 seat2 seat3 < <(curl -fs "$BOOKING_URL/api/shows/$show_id/seats" | jq -r '[.seats[0].id, .seats[1].id, .seats[2].id] | @tsv')
pass "created show $show_id with $(jq -r .totalSeats <<<"$show") seats"

# 1. Happy path: booking is held, then confirmed by the payment saga.
read -r code body < <(post_booking "$show_id" "smoke-$RUN_ID" "key-1" "$seat1" "$seat2")
[ "$code" = 201 ] || fail "expected 201 for new booking, got $code: $body"
booking_id=$(jq -r .id <<<"$body")
pass "booking $booking_id created (PENDING_PAYMENT, $(jq -r .amountCents <<<"$body") cents)"

# 2. Retrying with the same Idempotency-Key returns the same booking.
read -r code body < <(post_booking "$show_id" "smoke-$RUN_ID" "key-1" "$seat1" "$seat2")
[ "$code" = 200 ] && [ "$(jq -r .id <<<"$body")" = "$booking_id" ] || fail "retry should return 200 and the same booking, got $code"
pass "retry with the same Idempotency-Key returned the original booking"

# 3. Someone else cannot take the held seats.
read -r code body < <(post_booking "$show_id" "other-$RUN_ID" "key-2" "$seat2")
[ "$code" = 409 ] || fail "expected 409 for a held seat, got $code"
pass "competing booking for a held seat rejected with 409"

wait_for_status "$booking_id" CONFIRMED || fail "booking $booking_id was not confirmed"
pass "payment completed, booking CONFIRMED"
booked=$(curl -fs "$BOOKING_URL/api/shows/$show_id/seats" | jq -r '.summary.BOOKED')
[ "$booked" = 2 ] || fail "expected 2 BOOKED seats, got $booked"
pass "seats marked BOOKED"

# 4. A declined payment cancels the booking and frees the seat.
read -r code body < <(post_booking "$show_id" "decline-$RUN_ID" "key-3" "$seat3")
[ "$code" = 201 ] || fail "expected 201, got $code: $body"
declined_id=$(jq -r .id <<<"$body")
wait_for_status "$declined_id" CANCELLED || fail "declined booking $declined_id was not cancelled"
pass "declined payment cancelled booking $declined_id"
read -r code body < <(post_booking "$show_id" "smoke-$RUN_ID" "key-4" "$seat3")
[ "$code" = 201 ] || fail "released seat should be bookable again, got $code"
pass "released seat could be booked again"

echo "All smoke checks passed."
