package io.github.ayushkzz.seatbooking.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensation step of the booking saga: a payment succeeded for a booking that had already
 * expired or been cancelled, so the money has to go back.
 */
public record RefundRequested(
        UUID eventId,
        UUID bookingId,
        String reason,
        Instant requestedAt) {
}
