package io.github.ayushkzz.seatbooking.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.PaymentFailed;
import io.github.ayushkzz.seatbooking.common.events.RefundRequested;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs against real PostgreSQL and Kafka: Testcontainers by default, or localhost with ITEST_LOCAL=true.
 */
@SpringBootTest(properties = {
        "payment.simulation.failure-rate=0",
        "payment.simulation.latency-min=PT0S",
        "payment.simulation.latency-max=PT0S"})
class PaymentFlowIT {

    static final boolean LOCAL = Boolean.parseBoolean(System.getenv().getOrDefault("ITEST_LOCAL", "false"));
    static final PostgreSQLContainer POSTGRES = LOCAL ? null : new PostgreSQLContainer("postgres:16-alpine");
    static final KafkaContainer KAFKA = LOCAL ? null : new KafkaContainer("apache/kafka:4.1.0");
    private static final String CONSUMER_GROUP = "payment-service-it-" + UUID.randomUUID();

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
            registry.add("spring.datasource.url", () -> env.getOrDefault("ITEST_DB_URL", "jdbc:postgresql://localhost:5432/payments_test"));
            registry.add("spring.datasource.username", () -> env.getOrDefault("ITEST_DB_USER", "payment"));
            registry.add("spring.datasource.password", () -> env.getOrDefault("ITEST_DB_PASSWORD", "payment"));
            registry.add("spring.kafka.bootstrap-servers", () -> env.getOrDefault("ITEST_KAFKA", "localhost:9092"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    PaymentRepository payments;

    @Autowired
    ObjectMapper json;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void chargesTheBookingAndPublishesPaymentCompleted() {
        BookingCreated booking = bookingFor("alice", 45000);

        publish(Topics.BOOKING_CREATED, booking.bookingId(), booking);

        try (var probe = new KafkaProbe(bootstrapServers, Topics.PAYMENT_COMPLETED)) {
            var completed = json.readValue(probe.awaitValue(booking.bookingId().toString(), Duration.ofSeconds(20)),
                    PaymentCompleted.class);
            assertThat(completed.amountCents()).isEqualTo(45000);
            assertThat(payments.findByBookingId(booking.bookingId()))
                    .hasValueSatisfying(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED));
        }
    }

    @Test
    void declinedCardPublishesPaymentFailed() {
        BookingCreated booking = bookingFor("decline-bob", 25000);

        publish(Topics.BOOKING_CREATED, booking.bookingId(), booking);

        try (var probe = new KafkaProbe(bootstrapServers, Topics.PAYMENT_FAILED)) {
            var failed = json.readValue(probe.awaitValue(booking.bookingId().toString(), Duration.ofSeconds(20)),
                    PaymentFailed.class);
            assertThat(failed.reason()).isEqualTo("Card declined");
        }
    }

    @Test
    void redeliveredBookingIsChargedOnlyOnce() {
        BookingCreated booking = bookingFor("carol", 10000);

        publish(Topics.BOOKING_CREATED, booking.bookingId(), booking);
        publish(Topics.BOOKING_CREATED, booking.bookingId(), booking);

        try (var probe = new KafkaProbe(bootstrapServers, Topics.PAYMENT_COMPLETED)) {
            probe.awaitValue(booking.bookingId().toString(), Duration.ofSeconds(20));
        }
        // give the duplicate time to be processed, then check there is still exactly one charge
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payments.countByBookingId(booking.bookingId())).isEqualTo(1));
    }

    @Test
    void refundRequestRefundsASuccessfulPayment() {
        BookingCreated booking = bookingFor("dave", 30000);
        publish(Topics.BOOKING_CREATED, booking.bookingId(), booking);
        await().atMost(Duration.ofSeconds(20)).until(() -> payments.findByBookingId(booking.bookingId()).isPresent());

        publish(Topics.BOOKING_REFUND_REQUESTED, booking.bookingId(),
                new RefundRequested(UUID.randomUUID(), booking.bookingId(), "Booking was EXPIRED", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).until(() -> payments.findByBookingId(booking.bookingId())
                .map(p -> p.getStatus() == PaymentStatus.REFUNDED).orElse(false));
    }

    private static BookingCreated bookingFor(String userId, long amountCents) {
        Instant now = Instant.now();
        return new BookingCreated(UUID.randomUUID(), UUID.randomUUID(), 1L, userId, List.of(1L), amountCents,
                now, now.plus(Duration.ofMinutes(5)));
    }

    private void publish(String topic, UUID key, Object event) {
        kafka.send(topic, key.toString(), json.writeValueAsString(event)).join();
    }
}
