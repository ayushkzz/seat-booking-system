package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Seats are held and the booking is waiting for payment. */
public record BookingCreated(
        UUID eventId,
        UUID bookingId,
        long showId,
        String userId,
        List<Long> seatIds,
        long amountCents,
        Instant createdAt,
        Instant expiresAt) {
}
