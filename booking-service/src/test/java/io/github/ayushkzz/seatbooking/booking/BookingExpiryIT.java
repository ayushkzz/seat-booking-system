package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.common.events.BookingExpired;
import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.RefundRequested;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"booking.hold-ttl=PT1S", "booking.expiry-scan-interval=PT0.2S"})
class BookingExpiryIT extends AbstractIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void unpaidBookingExpiresAndALatePaymentIsRefunded() {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();
        UUID bookingId = UUID.fromString(body(book(show, "judy", unique("key"), seat)).get("id").asString());

        await().atMost(Duration.ofSeconds(20)).until(() -> bookingStatus(bookingId).equals("EXPIRED"));
        awaitSeatCount(show, "AVAILABLE", 1);
        try (var probe = new KafkaProbe(bootstrapServers, Topics.BOOKING_EXPIRED)) {
            var expired = json.readValue(probe.awaitValue(bookingId.toString(), Duration.ofSeconds(20)),
                    BookingExpired.class);
            assertThat(expired.userId()).isEqualTo("judy");
        }

        // The payment provider answers after the hold ran out: the saga compensates with a refund.
        kafka.send(Topics.PAYMENT_COMPLETED, bookingId.toString(), json.writeValueAsString(
                new PaymentCompleted(UUID.randomUUID(), bookingId, UUID.randomUUID(), 10000, Instant.now()))).join();

        try (var probe = new KafkaProbe(bootstrapServers, Topics.BOOKING_REFUND_REQUESTED)) {
            var refund = json.readValue(probe.awaitValue(bookingId.toString(), Duration.ofSeconds(20)),
                    RefundRequested.class);
            assertThat(refund.reason()).isEqualTo("Booking was EXPIRED");
        }
        assertThat(bookingStatus(bookingId)).isEqualTo("EXPIRED");
    }
}
