#!/usr/bin/env bash
# Sends a crowd of pretend users into a show's waiting room, for demos. Open the show in the
# browser first or afterwards: you will see your position count down and seats fill up as the
# crowd is let in a few at a time. Needs curl and jq.
#
#   ./scripts/simulate-crowd.sh [showId] [users]      (defaults: show 2, 25 users)
#
# It talks to booking-service directly, because the gateway would rate-limit so many "users"
# coming from one machine.
set -euo pipefail

SHOW_ID="${1:-2}"
USERS="${2:-25}"
BOOKING_URL="${BOOKING_URL:-http://localhost:8080}"
RUN="crowd-$(date +%s)"

capacity=$(curl -fs "$BOOKING_URL/api/shows/$SHOW_ID" | jq -r '.waitingRoomCapacity // empty') || {
  echo "Could not load show $SHOW_ID from $BOOKING_URL. Is the stack running?" >&2; exit 1; }
[ -n "$capacity" ] || { echo "Show $SHOW_ID has no waiting room. Try show 2." >&2; exit 1; }
echo "Show $SHOW_ID lets $capacity people in at a time. Sending $USERS users..."

pretend_user() {
  local user="$RUN-$1" ticket status seat
  ticket=$(curl -fs -X POST "$BOOKING_URL/api/shows/$SHOW_ID/queue" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$user\"}" | jq -r .ticketId) || return 0
  while true; do
    status=$(curl -s "$BOOKING_URL/api/shows/$SHOW_ID/queue/$ticket" | jq -r '.status // "GONE"')
    case "$status" in
      ADMITTED)
        sleep $((2 + RANDOM % 4)) # thinks about which seat to pick
        seat=$(curl -fs "$BOOKING_URL/api/shows/$SHOW_ID/seats" | jq -r '[.seats[] | select(.status == "AVAILABLE") | .id] | .[]' | shuf -n 1)
        if [ -n "$seat" ]; then
          curl -s -o /dev/null -X POST "$BOOKING_URL/api/shows/$SHOW_ID/bookings" -H 'Content-Type: application/json' \
            -H "Idempotency-Key: $user" -H "X-Queue-Ticket: $ticket" -d "{\"userId\":\"$user\",\"seatIds\":[$seat]}"
        fi
        # booked (turn used up) or gave up: either way make sure the place is free
        curl -s -o /dev/null -X DELETE "$BOOKING_URL/api/shows/$SHOW_ID/queue/$ticket"
        echo "  user $1 had their turn"
        return 0 ;;
      WAITING) sleep 1 ;;
      *) return 0 ;;
    esac
  done
}

for i in $(seq 1 "$USERS"); do
  pretend_user "$i" &
  sleep 0.05
done
wait
echo "The crowd is through."
