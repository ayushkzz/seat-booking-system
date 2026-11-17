# Seat Booking System

[![CI](https://github.com/ayushkzz/seat-booking-system/actions/workflows/ci.yml/badge.svg)](https://github.com/ayushkzz/seat-booking-system/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-ed8b00?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f?logo=springboot&logoColor=white)
![Kafka](https://img.shields.io/badge/Kafka-4.1-231f20?logo=apachekafka&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-dc382d?logo=redis&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169e1?logo=postgresql&logoColor=white)

An event-driven backend for booking seats at shows, built around the hard parts of ticketing:
**two people grabbing the same seat at the same moment, clients retrying requests, payments that
fail or arrive too late, and services that must stay consistent without distributed transactions.**

- **No double booking under concurrency.** 200 simultaneous requests for one seat produce exactly one booking (covered by an integration test).
- **Idempotent API.** Retrying a request with the same `Idempotency-Key` returns the original booking instead of creating a second one.
- **Transactional outbox.** A booking and its `booking.created` event are committed together, so the database and Kafka never disagree.
- **Saga with compensation.** Payment success confirms the booking, a decline releases the seats, and a payment that arrives after the hold expired triggers a refund.
- **Self-healing holds.** Unpaid bookings expire automatically and their seats return to sale.
- **Virtual waiting room.** High-demand shows let only a few users choose seats at a time. Everyone else waits in a fair first-come, first-served queue and is let in automatically.
- **Web app.** A React front end with a live seat map, the waiting room, booking progress and notifications.
- **API gateway with rate limiting.** One entry point; each client gets a Redis token bucket, with a stricter limit on creating bookings.
- **Measured, not assumed.** A k6 load test holds 500 requests/s at a booking p95 of 154 ms on a single 4-vCPU machine, then verifies zero double bookings and that three separate databases agree exactly. See [the results](docs/LOAD_TEST.md).

| Waiting room | Your turn |
| --- | --- |
| ![Waiting in the queue for a high-demand show](docs/images/app-waiting-room.png) | ![Choosing seats once admitted](docs/images/app-your-turn.png) |

![Grafana dashboard during a load test](docs/images/grafana-load-test.png)

## Architecture

```mermaid
flowchart LR
    client([Browser])
    web[Web app<br/>React, served by nginx]
    gateway[API gateway<br/>routing, rate limits]
    subgraph booking-service
        api[REST API]
        relay[Outbox relay]
        expiry[Expiry job]
    end
    pay[payment-service]
    notify[notification-service]
    redis[(Redis<br/>seat holds, waiting room,<br/>seat map cache, rate limits)]
    bookingsDb[(PostgreSQL<br/>bookings)]
    paymentsDb[(PostgreSQL<br/>payments)]
    notificationsDb[(PostgreSQL<br/>notifications)]
    kafka{{Kafka}}

    client --> web
    web -->|/api| gateway
    gateway --> api
    gateway --> notify
    gateway -.-> redis
    api -->|hold seats, Lua| redis
    api -->|seats, booking, outbox row| bookingsDb
    relay -->|poll outbox| bookingsDb
    relay -->|booking events| kafka
    kafka -->|booking.created,<br/>refund-requested| pay
    pay --> paymentsDb
    pay -->|payment.completed / failed| kafka
    kafka -->|confirm, cancel or refund| api
    kafka -->|booking.confirmed,<br/>cancelled, expired| notify
    notify --> notificationsDb
    expiry -->|release unpaid seats| bookingsDb
```

Each service owns its database. They communicate only through Kafka, and every message is keyed
by booking id, so all events for one booking are processed in order.

| Service | Port | Responsibility |
| --- | --- | --- |
| frontend | 5173 | The web app. Serves the React build and forwards `/api` to the gateway. |
| gateway | 8000 | Single entry point. Routes requests and rate-limits each client. |
| booking-service | 8080 | Shows, seat maps, the waiting room, seat holds, bookings, the outbox and the saga. |
| payment-service | 8081 | Simulated payment provider. Charges each booking at most once, and refunds. |
| notification-service | 8082 | Turns booking events into notifications, once per event. |

### Booking lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: POST /bookings<br/>seats HELD
    PENDING_PAYMENT --> CONFIRMED: payment.completed<br/>seats BOOKED
    PENDING_PAYMENT --> CANCELLED: payment.failed<br/>seats released
    PENDING_PAYMENT --> EXPIRED: hold ran out<br/>seats released
    EXPIRED --> EXPIRED: late payment.completed<br/>→ booking.refund-requested
    CANCELLED --> CANCELLED: late payment.completed<br/>→ booking.refund-requested
```

## How double booking is prevented

A booking request passes three independent guards. Each one alone would catch most conflicts;
together they stay correct even when one of them fails.

| Guard | Where | What it does |
| --- | --- | --- |
| **Idempotency lock** | Redis `SET NX` | Requests that share an `Idempotency-Key` never run concurrently. A completed retry returns the stored booking (unique on user + key in PostgreSQL). |
| **Atomic seat hold** | Redis Lua script | Holds *all* requested seats or none, in one atomic step. Rejects most conflicts in about a millisecond, without touching the database. Keys use a `{show:id}` hash tag so a show's seats share one Redis Cluster slot. |
| **Compare-and-set** | PostgreSQL | `UPDATE seats SET status='HELD' WHERE id IN (...) AND status='AVAILABLE'`. If fewer rows change than requested, the transaction rolls back. This is the source of truth: even if Redis loses a hold, a seat can't be sold twice (covered by a test that deletes the Redis keys). |

## The waiting room

When a popular show goes on sale, thousands of people arrive in the same second. Letting them all
at the seat map at once means almost everyone clicks a seat that is already gone. A show can
instead set a capacity: how many users may be choosing seats at the same time.

```mermaid
sequenceDiagram
    participant U as User
    participant B as booking-service
    participant R as Redis
    U->>B: POST /shows/{id}/queue
    B->>R: add ticket to the queue, admit if a place is free
    B-->>U: WAITING, position 12
    loop every 2 seconds
        U->>B: GET /shows/{id}/queue/{ticket}
        B-->>U: WAITING, position 7 ... 1
    end
    Note over B,R: someone books, leaves, or their turn runs out
    B->>R: admit the next ticket (atomic Lua script)
    U->>B: GET /shows/{id}/queue/{ticket}
    B-->>U: ADMITTED, turn ends in 3 minutes
    U->>B: POST /shows/{id}/bookings with X-Queue-Ticket
    B-->>U: 201, turn used up, next user admitted
```

- **Fair.** The queue is a Redis sorted set ordered by arrival number, so users are admitted strictly first come, first served.
- **Never over capacity.** Ending expired turns and admitting the next users is one Lua script. Two instances running it at once still cannot let in more users than the capacity.
- **No ghosts.** A waiting ticket expires unless its owner keeps checking their position, so people who closed the tab are skipped instead of wasting a turn.
- **Enforced on the server.** Bookings for such a show are refused with `403` unless the request carries a ticket that is admitted right now and belongs to that user. A successful booking ends the turn, which lets the next person in immediately.

Try it with a crowd: open http://localhost:5173, pick *Stadium Tour: Opening Night*, and run

```bash
./scripts/simulate-crowd.sh
```

25 pretend users join the queue ahead of or behind you, get in three at a time, and buy seats while
your position counts down.

## Reliability patterns

- **Transactional outbox.** `booking.created` is written to an `outbox_events` table in the same transaction as the booking. A relay publishes pending rows to Kafka, waits for the acknowledgement, then marks them as sent. Rows are claimed with `FOR UPDATE SKIP LOCKED`, so several instances can relay in parallel.
- **Idempotent consumers.** Delivery is at-least-once. payment-service stores one payment per booking (unique constraint), so a redelivered event re-publishes the stored result instead of charging twice. booking-service only acts on bookings still in `PENDING_PAYMENT`, under a row lock.
- **Dead-letter topics.** A message that keeps failing is retried 3 times, then moved to `<topic>-dlt` so it doesn't block the partition.
- **Expiry job.** Bookings not paid within the hold time (5 minutes by default) are expired, and their seats are released. It also uses `SKIP LOCKED`, so it's safe to run on every instance.
- **Rate limiting at the gateway.** Spring Cloud Gateway keeps a token bucket per client and route in Redis. Creating bookings is limited to 5 requests/s (burst 10) per client; everything else to 50/s (burst 100). Excess requests get `429`.
- **Seat map cache.** The rendered seat map is cached in Redis for one second. It is deliberately not evicted on every booking: during a rush that would make most reads miss. A seat map can be a second old, but a booking is always checked against the real data.
- **Threads.** Web requests run on Java 21 virtual threads. Kafka consumers run on platform threads, because a consumer blocked in `poll()` pins its carrier thread ([what the load test found](docs/LOAD_TEST.md#what-the-test-found)).

## API

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/shows` | Upcoming shows |
| `POST` | `/api/shows` | Create a show with a rows × seats layout, optionally with a `waitingRoomCapacity` |
| `GET` | `/api/shows/{id}` | Show details |
| `GET` | `/api/shows/{id}/seats` | Seat map with a summary per status |
| `POST` | `/api/shows/{id}/bookings` | Hold seats and start payment. Needs an `Idempotency-Key` header. `201` new, `200` replay, `409` seat taken, `403` not your turn (shows with a waiting room need the `X-Queue-Ticket` header) |
| `GET` | `/api/bookings/{id}` | Booking status |
| `POST` | `/api/shows/{id}/queue` | Join the show's waiting room. Returns a ticket with `WAITING` and a position, or `ADMITTED` |
| `GET` | `/api/shows/{id}/queue/{ticketId}` | The ticket's place in the queue. Poll it; it also keeps the place alive |
| `DELETE` | `/api/shows/{id}/queue/{ticketId}` | Leave the queue or give up the turn |
| `GET` | `/api/users/{userId}/notifications` | The user's 50 most recent notifications |

All of it is served through the gateway on port 8000. A client that sends requests too fast gets `429 Too Many Requests`.

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses with a stable `code`:

```json
{
  "status": 409,
  "title": "Conflict",
  "code": "seat-unavailable",
  "detail": "One or more of the requested seats are already held or booked.",
  "instance": "/api/shows/1/bookings"
}
```

### Try it

```bash
docker compose up -d --build
```

Then open **http://localhost:5173** for the web app, or use the API directly:

```bash
# Seat map of the demo show (10 rows × 20 seats)
curl -s localhost:8000/api/shows/1/seats | jq '.summary'
```

```bash
# Book two seats
curl -s -X POST localhost:8000/api/shows/1/bookings \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 6f1c2a' \
  -d '{"userId":"ayush","seatIds":[1,2]}' | jq
```

```bash
# A moment later the payment saga has confirmed it
curl -s localhost:8000/api/bookings/<id> | jq '.status'
```

```bash
# ...and the user has been notified
curl -s localhost:8000/api/users/ayush/notifications | jq
```

Users whose id starts with `decline` always have their payment declined, which is handy for trying
the cancellation path. Other payments fail randomly at `PAYMENT_FAILURE_RATE` (default 10%).

## Running the tests

```bash
./mvnw verify
```

The integration tests start the whole service against real PostgreSQL, Redis and Kafka using
[Testcontainers](https://testcontainers.com), so Docker must be running. They cover:

- 200 concurrent requests for one seat → exactly one `201`, 199 × `409`
- the database guard when the Redis hold is lost
- idempotent retries, and rejection of a reused key for a different request
- `booking.created` published through the outbox
- payment completed → confirmed, payment failed → seats released
- hold expiry, then a late payment → refund requested
- payment-service charging once per booking under redelivery, declines and refunds
- the waiting room: capacity is respected, the queue keeps its order, a booking admits the next user, turns expire, and absent users are skipped
- notification-service notifying once per event under redelivery
- gateway routing, and `429` once a client exceeds its booking rate

To reuse the Docker Compose infrastructure instead of starting new containers each time:

```bash
docker compose up -d postgres redis kafka
```

```bash
ITEST_LOCAL=true ./mvnw verify
```

`scripts/smoke-test.sh` runs the full saga end to end against running services. CI runs it against
the Docker Compose stack on every push.

## Local development

[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers setting up your machine, running the services
from an IDE against the Docker infrastructure, the Postman collection, configuration and
troubleshooting.

## Load test

`scripts/load-test.sh` starts the stack, runs a k6 test (seat map reads, bookings, and 300 users
rushing one seat), then checks the databases for double bookings and for agreement between the
services. Results and how to read them are in [docs/LOAD_TEST.md](docs/LOAD_TEST.md).

## Observability

```bash
docker compose --profile monitoring up -d --build
```

adds Prometheus (http://localhost:9090) and Grafana (http://localhost:3000, no login) with a
ready-made dashboard: booking outcomes, rejected seat conflicts by layer, API latency percentiles,
gateway traffic and rate limiting, cache hit rate, the waiting room, payments, notifications, Kafka consumer lag,
outbox backlog, database connections, CPU and heap.

Every service exposes `/actuator/health` (with liveness and readiness probes) and
`/actuator/prometheus`. Custom metrics include `bookings_placed_total`, `bookings_confirmed_total`,
`bookings_seat_conflicts_total{layer="redis|database"}`, `seatmap_cache_requests_total{result}`,
`outbox_pending`, `payments_processed_total{status}` and `notifications_sent_total{type}`.

## Project structure

```
common/                event records and topic names shared by the services
booking-service/       shows, seat holds, bookings, outbox relay, saga handlers, expiry job
payment-service/       simulated payment provider; idempotent charges and refunds
notification-service/  notifications from booking events, once per event
gateway/               Spring Cloud Gateway: routing and Redis rate limiting
frontend/              React + TypeScript web app (Vite), served by nginx in Docker
loadtest/              k6 load test
scripts/               end-to-end smoke test, load test runner, crowd simulator for demos
postman/               Postman collection that walks through the booking saga
docs/                  local development guide, load test results
docker/                PostgreSQL init script, Prometheus and Grafana configuration
```

## Tech stack

Java 21 (virtual threads) · Spring Boot 4 · Spring Cloud Gateway · Spring Data JPA / Hibernate ·
Flyway · Spring Kafka · Spring Data Redis · PostgreSQL 16 · Redis 7 · Apache Kafka 4 (KRaft) ·
Micrometer · Prometheus · Grafana · k6 · JUnit 5 · Testcontainers · Awaitility · React 19 · TypeScript ·
Vite · nginx · Docker Compose · GitHub Actions

The gateway runs on Spring Boot 4.0 and the other services on 4.1, because Spring Cloud Gateway
has no stable release for Boot 4.1 yet.

## Possible next steps

- Real email or SMS delivery behind the `NotificationSender` interface
- User accounts and authentication at the gateway (JWT), instead of a `userId` in the request
- Push seat and queue updates to the browser (Server-Sent Events) instead of polling
- Kubernetes manifests with horizontal scaling of booking-service
