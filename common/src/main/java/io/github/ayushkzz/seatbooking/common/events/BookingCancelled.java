package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.UUID;

/** Payment was declined and the seats were released. */
public record BookingCancelled(
        UUID eventId,
        UUID bookingId,
        long showId,
        String userId,
        String reason,
        Instant cancelledAt) {
}
