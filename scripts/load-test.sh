#!/usr/bin/env bash
# Runs the k6 load test against the Docker Compose stack and then checks the data for double
# bookings and for agreement between the three services. Needs Docker, curl and jq.
#
#   ./scripts/load-test.sh
#
# Tunables: BROWSE_RATE, BOOK_RATE (requests per second), DURATION, HOT_SEAT_USERS, SHOWS.
# Set KEEP_STACK=1 to leave the stack running afterwards (Grafana: http://localhost:3000).
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml:docker-compose.loadtest.yml}"
export COMPOSE_PROFILES=monitoring
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"

k6() {
  docker run --rm --network host -v "$PWD/loadtest:/scripts:ro" \
    -e BROWSE_RATE -e BOOK_RATE -e DURATION -e HOT_SEAT_USERS -e SHOWS "$K6_IMAGE" run --quiet "$@" /scripts/booking.js
}
sql() { docker compose exec -T postgres psql -U postgres -d "$1" -Atc "$2"; }

echo "==> Starting a fresh stack"
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d --build --wait
for port in 8080 8081 8082; do
  for _ in $(seq 1 90); do
    [ "$(curl -fs -m 3 "localhost:$port/actuator/health" | jq -r .status 2>/dev/null)" = UP ] && break
    sleep 2
  done
done

# Freshly started JVMs run interpreted code and are several times slower than after a minute of
# traffic, so the measured run starts from a warmed-up system, as a real deployment would be.
echo "==> Warm-up (90s, not measured)"
BROWSE_RATE=250 BOOK_RATE=60 DURATION=90s HOT_SEAT_USERS=50 SHOWS=30 k6 --no-thresholds --summary-mode=disabled

echo "==> Load test"
status=0
k6 || status=$?

echo "==> Waiting for the payment saga to settle"
for _ in $(seq 1 60); do
  pending=$(sql bookings "select count(*) from bookings where status = 'PENDING_PAYMENT'")
  backlog=$(sql bookings "select count(*) from outbox_events where published_at is null")
  [ "$pending" = 0 ] && [ "$backlog" = 0 ] && break
  sleep 2
done
sleep 3 # let the last notifications land

echo "==> Checking the data"
double_booked=$(sql bookings "select count(*) from (select bs.seat_id from booking_seats bs join bookings b on b.id = bs.booking_id where b.status in ('PENDING_PAYMENT', 'CONFIRMED') group by bs.seat_id having count(*) > 1) d")
confirmed=$(sql bookings "select count(*) from bookings where status = 'CONFIRMED'")
cancelled=$(sql bookings "select count(*) from bookings where status = 'CANCELLED'")
expired=$(sql bookings "select count(*) from bookings where status = 'EXPIRED'")
booked_seats=$(sql bookings "select count(*) from seats where status = 'BOOKED'")
confirmed_seats=$(sql bookings "select count(*) from booking_seats bs join bookings b on b.id = bs.booking_id where b.status = 'CONFIRMED'")
payments_ok=$(sql payments "select count(*) from payments where status = 'SUCCEEDED'")
payments_failed=$(sql payments "select count(*) from payments where status = 'FAILED'")
notifications=$(sql notifications "select count(*) from notifications")

verify() { # verify <description> <actual> <expected>
  if [ "$2" = "$3" ]; then printf '  \033[32m✔\033[0m %s (%s)\n' "$1" "$2"
  else printf '  \033[31m✘\033[0m %s: got %s, expected %s\n' "$1" "$2" "$3"; status=1; fi
}
echo "  bookings: $confirmed confirmed, $cancelled cancelled, $expired expired, $pending still pending"
verify "seats sold to more than one booking" "$double_booked" 0
verify "bookings left waiting for payment" "$pending" 0
verify "booked seats match confirmed bookings" "$booked_seats" "$confirmed_seats"
verify "successful payments match confirmed bookings" "$payments_ok" "$confirmed"
verify "failed payments match cancelled bookings" "$payments_failed" "$cancelled"
verify "one notification per finished booking" "$notifications" "$((confirmed + cancelled + expired))"

if [ "${KEEP_STACK:-0}" != 1 ]; then
  docker compose down -v >/dev/null 2>&1
fi
exit $status
