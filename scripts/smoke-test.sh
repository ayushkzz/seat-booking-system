#!/usr/bin/env bash
# End-to-end check against running services (local run or docker compose).
# Needs curl and jq. Expects the payment failure rate to be 0 so the happy path is deterministic:
#   PAYMENT_FAILURE_RATE=0 docker compose up -d --build
set -euo pipefail

# All API calls go through the gateway; the service URLs are only used for health checks.
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8000}"
BOOKING_URL="${BOOKING_URL:-http://localhost:8080}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8081}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8082}"
# The web app is only checked when it is running, unless REQUIRE_FRONTEND=1 (as in CI).
FRONTEND_URL="${FRONTEND_URL:-http://localhost:5173}"
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
  curl -s -o /tmp/smoke-body -w '%{http_code}' -X POST "$GATEWAY_URL/api/shows/$show/bookings" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d "{\"userId\":\"$user\",\"seatIds\":$seats}"
  printf ' %s\n' "$(cat /tmp/smoke-body)"
}

wait_for_status() {
  local booking=$1 expected=$2
  for _ in $(seq 1 60); do
    local status; status=$(curl -fs "$GATEWAY_URL/api/bookings/$booking" | jq -r .status)
    [ "$status" = "$expected" ] && return 0
    sleep 0.5
  done
  return 1
}

wait_for_notification() {
  local user=$1 type=$2
  for _ in $(seq 1 60); do
    local count; count=$(curl -fs "$GATEWAY_URL/api/users/$user/notifications" | jq "[.[] | select(.type == \"$type\")] | length")
    [ "$count" = 1 ] && return 0
    sleep 0.5
  done
  return 1
}

echo "Smoke test ($RUN_ID)"
wait_for_health booking-service "$BOOKING_URL"
wait_for_health payment-service "$PAYMENT_URL"
wait_for_health notification-service "$NOTIFICATION_URL"
wait_for_health gateway "$GATEWAY_URL"

show=$(curl -fs -X POST "$GATEWAY_URL/api/shows" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke Test","venue":"CI","startsAt":"2099-01-01T19:30:00Z","rows":2,"seatsPerRow":5,"priceCents":25000}')
show_id=$(jq -r .id <<<"$show")
read -r seat1 seat2 seat3 < <(curl -fs "$GATEWAY_URL/api/shows/$show_id/seats" | jq -r '[.seats[0].id, .seats[1].id, .seats[2].id] | @tsv')
pass "created show $show_id with $(jq -r .totalSeats <<<"$show") seats (through the gateway)"

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
booked=0
for _ in $(seq 1 10); do   # the seat map is cached for a second
  booked=$(curl -fs "$GATEWAY_URL/api/shows/$show_id/seats" | jq -r '.summary.BOOKED // 0')
  [ "$booked" = 2 ] && break
  sleep 0.5
done
[ "$booked" = 2 ] || fail "expected 2 BOOKED seats, got $booked"
pass "seats marked BOOKED"
wait_for_notification "smoke-$RUN_ID" BOOKING_CONFIRMED || fail "no confirmation notification for smoke-$RUN_ID"
pass "user was notified that the booking is confirmed"

# 4. A declined payment cancels the booking and frees the seat.
read -r code body < <(post_booking "$show_id" "decline-$RUN_ID" "key-3" "$seat3")
[ "$code" = 201 ] || fail "expected 201, got $code: $body"
declined_id=$(jq -r .id <<<"$body")
wait_for_status "$declined_id" CANCELLED || fail "declined booking $declined_id was not cancelled"
pass "declined payment cancelled booking $declined_id"
wait_for_notification "decline-$RUN_ID" BOOKING_CANCELLED || fail "no cancellation notification for decline-$RUN_ID"
pass "user was notified that the booking was cancelled"
read -r code body < <(post_booking "$show_id" "smoke-$RUN_ID" "key-4" "$seat3")
[ "$code" = 201 ] || fail "released seat should be bookable again, got $code"
pass "released seat could be booked again"

# 5. Waiting room: only one user at a time may book this show.
queue_show=$(curl -fs -X POST "$GATEWAY_URL/api/shows" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke Test Rush","venue":"CI","startsAt":"2099-01-01T19:30:00Z","rows":1,"seatsPerRow":4,"priceCents":25000,"waitingRoomCapacity":1}' | jq -r .id)
queue_seat=$(curl -fs "$GATEWAY_URL/api/shows/$queue_show/seats" | jq -r '.seats[0].id')
join_queue() { curl -fs -X POST "$GATEWAY_URL/api/shows/$queue_show/queue" -H 'Content-Type: application/json' -d "{\"userId\":\"$1\"}"; }
first=$(join_queue "first-$RUN_ID"); second=$(join_queue "second-$RUN_ID")
[ "$(jq -r .status <<<"$first")" = ADMITTED ] && [ "$(jq -r '"\(.status) \(.position)"' <<<"$second")" = "WAITING 1" ] \
  || fail "expected the first user to be admitted and the second to wait at position 1"
pass "waiting room admitted the first user and queued the second"
read -r code body < <(post_booking "$queue_show" "second-$RUN_ID" "key-5" "$queue_seat")
[ "$code" = 403 ] || fail "booking without an admitted ticket should be refused with 403, got $code"
pass "booking without a turn refused with 403"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY_URL/api/shows/$queue_show/bookings" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: key-6" -H "X-Queue-Ticket: $(jq -r .ticketId <<<"$first")" \
  -d "{\"userId\":\"first-$RUN_ID\",\"seatIds\":[$queue_seat]}")
[ "$code" = 201 ] || fail "admitted user should be able to book, got $code"
status=WAITING
for _ in $(seq 1 20); do
  status=$(curl -fs "$GATEWAY_URL/api/shows/$queue_show/queue/$(jq -r .ticketId <<<"$second")" | jq -r .status)
  [ "$status" = ADMITTED ] && break
  sleep 0.5
done
[ "$status" = ADMITTED ] || fail "the second user should be admitted once the first has booked"
pass "admitted user booked, and the next user in line was let in"

# 6. The web app is served and can reach the API through its own origin.
if curl -fs -m 5 -o /tmp/smoke-frontend "$FRONTEND_URL/"; then
  grep -q '<div id="root">' /tmp/smoke-frontend || fail "the web app page at $FRONTEND_URL looks wrong"
  [ "$(curl -fs "$FRONTEND_URL/api/shows" | jq 'length > 0')" = true ] || fail "the web app cannot reach the API"
  pass "web app is served and reaches the API"
elif [ "${REQUIRE_FRONTEND:-0}" = 1 ]; then
  fail "the web app is not reachable at $FRONTEND_URL"
else
  echo "  - web app not running at $FRONTEND_URL, skipped"
fi

# 7. The gateway limits how fast one client can create bookings.
limited=0
for i in $(seq 1 40); do
  read -r code body < <(post_booking "$show_id" "burst-$RUN_ID" "burst-$i" "$seat1")
  [ "$code" = 429 ] && limited=$((limited + 1))
done
[ "$limited" -gt 0 ] || fail "expected the gateway to rate-limit a burst of 40 booking requests"
pass "gateway rate-limited $limited of 40 rapid booking requests (429)"

echo "All smoke checks passed."
