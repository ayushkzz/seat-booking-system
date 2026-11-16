package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.ayushkzz.seatbooking.booking.booking.BookingRepository;
import io.github.ayushkzz.seatbooking.common.events.BookingCancelled;
import io.github.ayushkzz.seatbooking.common.events.BookingConfirmed;
import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.PaymentFailed;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BookingFlowIT extends AbstractIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    BookingRepository bookings;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry metrics;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void holdsSeatsAndPublishesBookingCreatedThroughTheOutbox() {
        long show = createShow(1, 4, 30000);
        List<Long> seats = seatIds(show);

        var response = book(show, "alice", unique("key"), seats.get(0), seats.get(1));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var booking = body(response);
        assertThat(booking.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(booking.get("amountCents").asLong()).isEqualTo(60000);
        awaitSeatCount(show, "HELD", 2);

        try (var probe = new KafkaProbe(bootstrapServers, Topics.BOOKING_CREATED)) {
            String bookingId = booking.get("id").asString();
            var event = json.readValue(probe.awaitValue(bookingId, Duration.ofSeconds(20)), BookingCreated.class);
            assertThat(event.showId()).isEqualTo(show);
            assertThat(event.seatIds()).containsExactly(seats.get(0), seats.get(1));
            assertThat(event.amountCents()).isEqualTo(60000);
        }
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheOriginalBooking() {
        long show = createShow(1, 2, 10000);
        Long seat = seatIds(show).getFirst();
        String key = unique("key");

        var first = book(show, "bob", key, seat);
        var retry = book(show, "bob", key, seat);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(body(retry).get("id")).isEqualTo(body(first).get("id"));
        assertThat(bookings.countByShowId(show)).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentRequestIsRejected() {
        long show = createShow(1, 2, 10000);
        List<Long> seats = seatIds(show);
        String key = unique("key");

        book(show, "carol", key, seats.get(0));
        var reused = book(show, "carol", key, seats.get(1));

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(body(reused).get("code").asString()).isEqualTo("idempotency-key-reused");
    }

    @Test
    void onlyOneOfManyConcurrentRequestsGetsTheSeat() throws Exception {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();
        int requests = 200;

        Map<Integer, Long> statusCounts;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = IntStream.range(0, requests)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return book(show, "user-" + i, unique("key"), seat).getStatusCode().value();
                    }))
                    .toList();
            start.countDown();
            statusCounts = results.stream().map(BookingFlowIT::join)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        }

        assertThat(statusCounts).containsOnlyKeys(201, 409);
        assertThat(statusCounts.get(201)).isEqualTo(1);
        assertThat(statusCounts.get(409)).isEqualTo(requests - 1);
        assertThat(bookings.countByShowId(show)).isEqualTo(1);
    }

    @Test
    void databaseStillPreventsDoubleBookingWhenTheRedisHoldIsLost() {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();
        assertThat(book(show, "dave", unique("key"), seat).getStatusCode().value()).isEqualTo(201);

        // Simulate Redis losing the hold (eviction, failover, flush...).
        redis.delete(redis.keys("hold:{show:" + show + "}:*"));
        var second = book(show, "erin", unique("key"), seat);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(body(second).get("code").asString()).isEqualTo("seat-unavailable");
        assertThat(bookings.countByShowId(show)).isEqualTo(1);
    }

    @Test
    void paymentCompletedConfirmsTheBookingAndBooksTheSeats() {
        long show = createShow(1, 2, 10000);
        List<Long> seats = seatIds(show);
        UUID bookingId = UUID.fromString(body(book(show, "frank", unique("key"), seats.get(0), seats.get(1)))
                .get("id").asString());

        publish(Topics.PAYMENT_COMPLETED, bookingId,
                new PaymentCompleted(UUID.randomUUID(), bookingId, UUID.randomUUID(), 20000, Instant.now()));

        await().atMost(Duration.ofSeconds(20)).until(() -> bookingStatus(bookingId).equals("CONFIRMED"));
        awaitSeatCount(show, "BOOKED", 2);
        try (var probe = new KafkaProbe(bootstrapServers, Topics.BOOKING_CONFIRMED)) {
            var confirmed = json.readValue(probe.awaitValue(bookingId.toString(), Duration.ofSeconds(20)),
                    BookingConfirmed.class);
            assertThat(confirmed.userId()).isEqualTo("frank");
            assertThat(confirmed.seatIds()).containsExactlyElementsOf(seats);
        }
    }

    @Test
    void paymentFailedCancelsTheBookingAndReleasesTheSeats() {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();
        UUID bookingId = UUID.fromString(body(book(show, "grace", unique("key"), seat)).get("id").asString());

        publish(Topics.PAYMENT_FAILED, bookingId,
                new PaymentFailed(UUID.randomUUID(), bookingId, "Card declined", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).until(() -> bookingStatus(bookingId).equals("CANCELLED"));
        awaitSeatCount(show, "AVAILABLE", 1);
        assertThat(book(show, "heidi", unique("key"), seat).getStatusCode().value()).isEqualTo(201);
        try (var probe = new KafkaProbe(bootstrapServers, Topics.BOOKING_CANCELLED)) {
            var cancelled = json.readValue(probe.awaitValue(bookingId.toString(), Duration.ofSeconds(20)),
                    BookingCancelled.class);
            assertThat(cancelled.reason()).isEqualTo("Card declined");
        }
    }

    @Test
    void seatMapIsServedFromTheCacheAndRefreshesWithinASecond() {
        long show = createShow(1, 3, 10000);
        double hitsBefore = cacheRequests("hit");
        double missesBefore = cacheRequests("miss");

        seatMap(show);
        seatMap(show);
        seatMap(show);

        assertThat(cacheRequests("miss") - missesBefore).isEqualTo(1);
        assertThat(cacheRequests("hit") - hitsBefore).isEqualTo(2);

        Long seat = seatIds(show).getFirst();
        book(show, "ken", unique("key"), seat);

        awaitSeatCount(show, "HELD", 1);
    }

    private double cacheRequests(String result) {
        return metrics.counter("seatmap.cache.requests", "result", result).count();
    }

    @Test
    void rejectsInvalidRequests() {
        long show = createShow(1, 1, 10000);
        Long seat = seatIds(show).getFirst();

        ResponseEntity<String> missingKey = http().post().uri("/api/shows/{id}/bookings", show)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", "ivan", "seatIds", List.of(seat)))
                .retrieve().toEntity(String.class);
        assertThat(missingKey.getStatusCode().value()).isEqualTo(400);

        assertThat(book(show, "ivan", unique("key")).getStatusCode().value()).isEqualTo(400);
        assertThat(book(show, "ivan", unique("key"), 999_999_999L).getStatusCode().value()).isEqualTo(404);
        assertThat(book(999_999_999L, "ivan", unique("key"), seat).getStatusCode().value()).isEqualTo(404);
    }

    private void publish(String topic, UUID key, Object event) {
        kafka.send(topic, key.toString(), json.writeValueAsString(event)).join();
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
