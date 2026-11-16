package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole service against real PostgreSQL, Redis and Kafka. By default they run in
 * Testcontainers; set ITEST_LOCAL=true to use instances on localhost instead (see README).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final boolean LOCAL = Boolean.parseBoolean(System.getenv().getOrDefault("ITEST_LOCAL", "false"));

    static final PostgreSQLContainer POSTGRES;
    static final GenericContainer<?> REDIS;
    static final KafkaContainer KAFKA;

    static {
        if (LOCAL) {
            POSTGRES = null;
            REDIS = null;
            KAFKA = null;
        } else {
            POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
            REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            KAFKA = new KafkaContainer("apache/kafka:4.1.0");
            Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
        }
    }

    /** Own consumer group per test run, so other consumers on a shared broker never steal test events. */
    private static final String CONSUMER_GROUP = "booking-service-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.consumer.group-id", () -> CONSUMER_GROUP);
        if (LOCAL) {
            registry.add("spring.datasource.url", () -> env("ITEST_DB_URL", "jdbc:postgresql://localhost:5432/bookings_test"));
            registry.add("spring.datasource.username", () -> env("ITEST_DB_USER", "booking"));
            registry.add("spring.datasource.password", () -> env("ITEST_DB_PASSWORD", "booking"));
            registry.add("spring.data.redis.host", () -> env("ITEST_REDIS_HOST", "localhost"));
            registry.add("spring.kafka.bootstrap-servers", () -> env("ITEST_KAFKA", "localhost:9092"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        }
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    @LocalServerPort
    int port;

    @Autowired
    protected ObjectMapper json;

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    protected JsonNode body(ResponseEntity<String> response) {
        return json.readTree(response.getBody());
    }

    /** Creates a fresh show so every test works on its own seats. */
    protected long createShow(int rows, int seatsPerRow, long priceCents) {
        var response = http().post().uri("/api/shows").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", "Test show", "venue", "Test hall", "startsAt", "2099-01-01T19:30:00Z",
                        "rows", rows, "seatsPerRow", seatsPerRow, "priceCents", priceCents))
                .retrieve().toEntity(String.class);
        return body(response).get("id").asLong();
    }

    protected List<Long> seatIds(long showId) {
        var seats = body(http().get().uri("/api/shows/{id}/seats", showId).retrieve().toEntity(String.class));
        return seats.get("seats").valueStream().map(s -> s.get("id").asLong()).toList();
    }

    protected JsonNode seatMap(long showId) {
        return body(http().get().uri("/api/shows/{id}/seats", showId).retrieve().toEntity(String.class));
    }

    /** Waits until the seat map shows this many seats in a status (seat maps are cached for a second). */
    protected void awaitSeatCount(long showId, String status, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(seatMap(showId).get("summary").path(status).asInt(0)).isEqualTo(expected));
    }

    protected ResponseEntity<String> book(long showId, String userId, String idempotencyKey, Long... seatIds) {
        return http().post().uri("/api/shows/{id}/bookings", showId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("userId", userId, "seatIds", List.of(seatIds)))
                .retrieve().toEntity(String.class);
    }

    protected String bookingStatus(UUID bookingId) {
        return body(http().get().uri("/api/bookings/{id}", bookingId).retrieve().toEntity(String.class))
                .get("status").asString();
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
