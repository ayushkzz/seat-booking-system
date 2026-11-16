package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payment succeeded and the seats now belong to the customer. */
public record BookingConfirmed(
        UUID eventId,
        UUID bookingId,
        long showId,
        String userId,
        List<Long> seatIds,
        long amountCents,
        Instant confirmedAt) {
}
