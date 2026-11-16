package io.github.ayushkzz.seatbooking.common.events;

import java.util.List;

/** Kafka topic names. Every message is keyed by booking id, so all events for a booking stay in order. */
public final class Topics {

    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_EXPIRED = "booking.expired";
    public static final String BOOKING_REFUND_REQUESTED = "booking.refund-requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";

    public static final List<String> ALL = List.of(BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_CANCELLED,
            BOOKING_EXPIRED, BOOKING_REFUND_REQUESTED, PAYMENT_COMPLETED, PAYMENT_FAILED);

    private Topics() {
    }
}
