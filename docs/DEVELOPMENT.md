# Local development

Everything you need to clone the project and work on it on your own machine.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 or newer | [Eclipse Temurin](https://adoptium.net) works well. Check with `java -version`. |
| Docker | Docker Desktop, or Docker Engine with Compose v2 | Runs PostgreSQL, Redis and Kafka, and the integration tests. |
| Node.js | 22 or newer | Only needed to work on the front end outside Docker. |
| Git | any recent | |

Maven does not need to be installed: the `mvnw` wrapper downloads the right version on first use.

**On Windows**, use `mvnw.cmd` instead of `./mvnw` in PowerShell or cmd (for example
`.\mvnw.cmd verify`). The shell script in `scripts/` needs bash, so run it from WSL or Git Bash.

## Get the code

```bash
git clone https://github.com/ayushkzz/seat-booking-system.git
cd seat-booking-system
```

## Ways to run it

### 1. Everything in Docker

The quickest way to see it working. Builds the gateway and the three services and starts them with their infrastructure.

```bash
docker compose up -d --build
```

Open the web app on **http://localhost:5173**. The API is served through the gateway on
http://localhost:8000. To also get Prometheus and the
Grafana dashboard (http://localhost:3000, no login), add the monitoring profile:

```bash
docker compose --profile monitoring up -d --build
```

Stop it with:

```bash
docker compose down
```

Add `-v` to also delete the database volume and start from a clean state next time.

### 2. Infrastructure in Docker, services on your machine

Best for day-to-day work: restarts are fast and you can attach a debugger.

```bash
docker compose up -d postgres redis kafka
```

PostgreSQL, Redis and Kafka are now on their default ports on localhost, which is where the
services look by default, so no configuration is needed. Then either:

- **VS Code:** open the folder, install the recommended extensions when prompted, and start
  **All services** from the Run and Debug panel (`.vscode/launch.json`).
- **IntelliJ IDEA:** open the folder as a Maven project and run `BookingServiceApplication`,
  `PaymentServiceApplication`, `NotificationServiceApplication` and `GatewayApplication`.
- **Terminal:**

  ```bash
  ./mvnw package -DskipTests
  ```

  ```bash
  java -jar booking-service/target/booking-service.jar
  ```

  ```bash
  java -jar payment-service/target/payment-service.jar
  ```

  ```bash
  java -jar notification-service/target/notification-service.jar
  ```

  ```bash
  java -jar gateway/target/gateway.jar
  ```

You only need the services you are working on: booking-service runs fine alone, but bookings stay
`PENDING_PAYMENT` without payment-service.

## Working on the front end

The web app lives in `frontend/` (React, TypeScript, Vite). Start the backend with either option
above, then run the development server, which reloads on every change:

```bash
cd frontend
```

```bash
npm install
```

```bash
npm run dev
```

It serves the app on http://localhost:5173 and forwards `/api` to the gateway on port 8000, so
stop the `frontend` container first if the full Docker stack is running
(`docker compose stop frontend`). `npm run build` type-checks and builds the production bundle.

There are no accounts: the name in the top bar is sent as the `userId`. Open a second browser
profile or change the name to act as another user.

To see the waiting room under pressure, open *Stadium Tour: Opening Night* and run:

```bash
./scripts/simulate-crowd.sh
```

It sends 25 pretend users into the queue (`./scripts/simulate-crowd.sh <showId> <users>` to change that).

## Try the API

- **Postman:** import `postman/seat-booking-system.postman_collection.json` and run the requests
  in order. Each one saves the ids the next one needs.
- **Script:** `scripts/smoke-test.sh` runs the whole booking saga and checks every step. It needs
  `curl` and `jq`, and expects no random payment failures:

  ```bash
  PAYMENT_FAILURE_RATE=0 docker compose up -d --build
  ```

  ```bash
  ./scripts/smoke-test.sh
  ```

- **curl:** see the examples in the [README](../README.md#try-it).

Payments are simulated. 10% are declined at random by default; change that with
`PAYMENT_FAILURE_RATE` (copy `.env.example` to `.env` for Docker Compose, or set the environment
variable when running payment-service yourself). A `userId` starting with `decline` is always declined.

## Tests

```bash
./mvnw verify
```

This runs the unit tests and the integration tests. The integration tests start their own
PostgreSQL, Redis and Kafka containers through Testcontainers, so Docker must be running, but
`docker compose` does not need to be up. The first run downloads the images and takes a few minutes.

Useful variations:

```bash
# One module
./mvnw verify -pl booking-service -am
```

```bash
# One test class
./mvnw verify -pl booking-service -am -Dit.test=BookingFlowIT
```

```bash
# Reuse the Docker Compose infrastructure instead of starting new containers (faster)
docker compose up -d postgres redis kafka
ITEST_LOCAL=true ./mvnw verify
```

Test classes ending in `Test` are unit tests (Surefire). Classes ending in `IT` are integration
tests (Failsafe) and run in the `verify` phase.

## Load test

```bash
./scripts/load-test.sh
```

Starts a fresh stack, runs the k6 load test and checks the data afterwards. It takes about five
minutes. Details and results are in [LOAD_TEST.md](LOAD_TEST.md).

## Ports and connections

| What | Address | Credentials |
| --- | --- | --- |
| Web app | http://localhost:5173 | |
| Gateway (use this for the API) | http://localhost:8000 | |
| booking-service | http://localhost:8080 | |
| payment-service | http://localhost:8081 | |
| notification-service | http://localhost:8082 | |
| Grafana (monitoring profile) | http://localhost:3000 | no login |
| Prometheus (monitoring profile) | http://localhost:9090 | |
| PostgreSQL | `localhost:5432` | databases `bookings`, `payments` and `notifications`; user and password are `booking`, `payment` and `notification` |
| Redis | `localhost:6379` | |
| Kafka | `localhost:9092` | |
| Health | `/actuator/health` on each service | |
| Metrics | `/actuator/prometheus` on each service | |

Peek inside while it runs:

```bash
# Bookings table
docker compose exec postgres psql -U booking -d bookings -c "select id, status, amount_cents from bookings order by created_at desc limit 5"
```

```bash
# Current seat holds in Redis
docker compose exec redis redis-cli keys 'hold:*'
```

```bash
# Events on a topic
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic booking.created --from-beginning
```

## Configuration

The services read their settings from environment variables, with defaults for local use.

| Variable | Default | Used by |
| --- | --- | --- |
| `PORT` | `8000`, `8080`, `8081`, `8082` | all |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local PostgreSQL | booking, payment, notification |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | booking-service, gateway |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | booking, payment, notification |
| `SEAT_MAP_CACHE_TTL` | `PT1S` | booking-service |
| `WAITING_ROOM_SESSION_TTL` | `PT3M` | booking-service |
| `PAYMENT_FAILURE_RATE` | `0.1` | payment-service |
| `BOOKING_SERVICE_URL`, `NOTIFICATION_SERVICE_URL` | `http://localhost:8080`, `http://localhost:8082` | gateway |
| `RATE_LIMIT_BOOKINGS_PER_SECOND`, `RATE_LIMIT_BOOKINGS_BURST` | `5`, `10` | gateway |
| `RATE_LIMIT_PER_SECOND`, `RATE_LIMIT_BURST` | `50`, `100` | gateway |

Other settings (seat hold time, expiry scan interval, simulated payment latency) are in each
service's `src/main/resources/application.yml`.

## Making changes

- **Database changes** go in a new Flyway migration:
  `booking-service/src/main/resources/db/migration/V2__describe_the_change.sql`. Never edit a
  migration that has already run. Hibernate only validates the schema (`ddl-auto: validate`), so
  the entity and the migration must match.
- **Events** shared between services live in `common/`. After changing one, rebuild with
  `./mvnw package -DskipTests` so both services pick it up.
- **Every push** runs the tests and a full Docker Compose smoke test in GitHub Actions
  (`.github/workflows/ci.yml`). Run `./mvnw verify` before pushing to catch failures early.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `port is already allocated` | Something else is using 5173, 5432, 6379, 9092, 8000 or 8080–8082. Stop it, or change the left side of the port mapping in `docker-compose.yml`. |
| Tests fail with `Could not find a valid Docker environment` | Docker isn't running. Start Docker Desktop and retry. |
| Service fails at startup with a Flyway validation error | The local database has an older schema. Reset it with `docker compose down -v`. |
| `./mvnw: Permission denied` (Linux/macOS) | `chmod +x mvnw` |
| `mvnw` or a script fails with `\r: command not found` | The file was checked out with Windows line endings. Run `git add --renormalize . && git checkout -- .` |
| Requests return `429 Too Many Requests` | The gateway's rate limit. Slow down, call the service directly on its own port, or raise the `RATE_LIMIT_*` settings. |
| Seat map doesn't show a booking you just made | Seat maps are cached for one second. Ask again. |
| Booking returns `403` | The show has a waiting room. Join the queue and send the admitted ticket in `X-Queue-Ticket`. |
| Booking stays `PENDING_PAYMENT` | payment-service isn't running, or can't reach Kafka. Check its log. Unpaid bookings expire after 5 minutes. |
