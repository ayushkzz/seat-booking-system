package io.github.ayushkzz.seatbooking.booking.booking;

public enum BookingStatus {
    /** Seats are held; waiting for the payment result. */
    PENDING_PAYMENT,
    CONFIRMED,
    /** Payment was declined; seats were released. */
    CANCELLED,
    /** Payment did not arrive before the hold ran out; seats were released. */
    EXPIRED
}
