package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.UUID;

/** The customer was charged for the booking. */
public record PaymentCompleted(
        UUID eventId,
        UUID bookingId,
        UUID paymentId,
        long amountCents,
        Instant completedAt) {
}
