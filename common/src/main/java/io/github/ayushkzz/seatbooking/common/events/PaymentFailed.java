package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.UUID;

/** The charge was declined; the booking should release its seats. */
public record PaymentFailed(
        UUID eventId,
        UUID bookingId,
        String reason,
        Instant failedAt) {
}
