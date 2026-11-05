package io.github.ayushkzz.seatbooking.booking.booking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class BookingMetrics {

    private final Counter created;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Counter expired;
    private final Counter redisConflicts;
    private final Counter databaseConflicts;

    BookingMetrics(MeterRegistry registry) {
        created = Counter.builder("bookings.placed").register(registry);
        confirmed = Counter.builder("bookings.confirmed").register(registry);
        cancelled = Counter.builder("bookings.cancelled").register(registry);
        expired = Counter.builder("bookings.expired").register(registry);
        redisConflicts = Counter.builder("bookings.seat.conflicts").tag("layer", "redis")
                .description("Requests rejected because a seat was already held").register(registry);
        databaseConflicts = Counter.builder("bookings.seat.conflicts").tag("layer", "database")
                .description("Requests that passed Redis but lost the database compare-and-set").register(registry);
    }

    void created() {
        created.increment();
    }

    void confirmed() {
        confirmed.increment();
    }

    void cancelled() {
        cancelled.increment();
    }

    void expired() {
        expired.increment();
    }

    void redisConflict() {
        redisConflicts.increment();
    }

    void databaseConflict() {
        databaseConflicts.increment();
    }
}
