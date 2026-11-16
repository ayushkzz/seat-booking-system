package io.github.ayushkzz.seatbooking.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.common.events.BookingCancelled;
import io.github.ayushkzz.seatbooking.common.events.BookingConfirmed;
import io.github.ayushkzz.seatbooking.common.events.BookingExpired;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs against real PostgreSQL and Kafka: Testcontainers by default, or localhost with ITEST_LOCAL=true.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationFlowIT {

    static final boolean LOCAL = Boolean.parseBoolean(System.getenv().getOrDefault("ITEST_LOCAL", "false"));
    static final PostgreSQLContainer POSTGRES = LOCAL ? null : new PostgreSQLContainer("postgres:16-alpine");
    static final KafkaContainer KAFKA = LOCAL ? null : new KafkaContainer("apache/kafka:4.1.0");
    private static final String CONSUMER_GROUP = "notification-service-it-" + UUID.randomUUID();

    static {
        if (!LOCAL) {
            Startables.deepStart(POSTGRES, KAFKA).join();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.consumer.group-id", () -> CONSUMER_GROUP);
        if (LOCAL) {
            var env = System.getenv();
            registry.add("spring.datasource.url", () -> env.getOrDefault("ITEST_DB_URL", "jdbc:postgresql://localhost:5432/notifications_test"));
            registry.add("spring.datasource.username", () -> env.getOrDefault("ITEST_DB_USER", "notification"));
            registry.add("spring.datasource.password", () -> env.getOrDefault("ITEST_DB_PASSWORD", "notification"));
            registry.add("spring.kafka.bootstrap-servers", () -> env.getOrDefault("ITEST_KAFKA", "localhost:9092"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    ObjectMapper json;

    @Test
    void confirmedBookingNotifiesTheUser() {
        String user = "alice-" + UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        publish(Topics.BOOKING_CONFIRMED, bookingId, new BookingConfirmed(UUID.randomUUID(), bookingId, 7L, user,
                List.of(1L, 2L), 90050, Instant.now()));

        JsonNode notification = awaitNotifications(user, 1).get(0);
        assertThat(notification.get("type").asString()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(notification.get("bookingId").asString()).isEqualTo(bookingId.toString());
        assertThat(notification.get("message").asString())
                .isEqualTo("Your booking is confirmed: 2 seats for show 7. Amount paid: 900.50.");
    }

    @Test
    void cancelledAndExpiredBookingsNotifyTheUser() {
        String user = "bob-" + UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        UUID expired = UUID.randomUUID();

        publish(Topics.BOOKING_CANCELLED, cancelled,
                new BookingCancelled(UUID.randomUUID(), cancelled, 7L, user, "Card declined", Instant.now()));
        publish(Topics.BOOKING_EXPIRED, expired,
                new BookingExpired(UUID.randomUUID(), expired, 7L, user, Instant.now()));

        JsonNode list = awaitNotifications(user, 2);
        assertThat(list.valueStream().map(n -> n.get("type").asString()))
                .containsExactlyInAnyOrder("BOOKING_CANCELLED", "BOOKING_EXPIRED");
        assertThat(list.valueStream().map(n -> n.get("message").asString()))
                .anyMatch(message -> message.contains("Card declined"));
    }

    @Test
    void redeliveredEventNotifiesOnlyOnce() {
        String user = "carol-" + UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var event = new BookingConfirmed(UUID.randomUUID(), bookingId, 7L, user, List.of(1L), 10000, Instant.now());

        publish(Topics.BOOKING_CONFIRMED, bookingId, event);
        publish(Topics.BOOKING_CONFIRMED, bookingId, event);

        awaitNotifications(user, 1);
        // give the duplicate time to be processed, then check there is still exactly one notification
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(notifications.countByBookingId(bookingId)).isEqualTo(1));
    }

    @Test
    void userWithoutNotificationsGetsAnEmptyList() {
        assertThat(notificationsFor("nobody-" + UUID.randomUUID()).size()).isZero();
    }

    private JsonNode awaitNotifications(String user, int expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> notificationsFor(user).size() >= expected);
        return notificationsFor(user);
    }

    private JsonNode notificationsFor(String user) {
        String body = RestClient.create("http://localhost:" + port).get()
                .uri("/api/users/{user}/notifications", user).retrieve().body(String.class);
        return json.readTree(body);
    }

    private void publish(String topic, UUID key, Object event) {
        kafka.send(topic, key.toString(), json.writeValueAsString(event)).join();
    }
}
