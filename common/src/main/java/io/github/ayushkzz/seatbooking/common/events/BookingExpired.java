package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.UUID;

/** The booking was not paid before its hold ran out and the seats were released. */
public record BookingExpired(
        UUID eventId,
        UUID bookingId,
        long showId,
        String userId,
        Instant expiredAt) {
}
