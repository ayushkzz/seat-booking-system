package io.github.ayushkzz.seatbooking.booking.api;

import org.springframework.http.HttpStatus;

/** An error that maps to an RFC 9457 problem response with a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException showNotFound(long showId) {
        return new ApiException(HttpStatus.NOT_FOUND, "show-not-found", "Show " + showId + " does not exist.");
    }

    public static ApiException bookingNotFound(Object bookingId) {
        return new ApiException(HttpStatus.NOT_FOUND, "booking-not-found", "Booking " + bookingId + " does not exist.");
    }

    public static ApiException seatsNotFound(long showId) {
        return new ApiException(HttpStatus.NOT_FOUND, "seat-not-found",
                "One or more seats do not exist for show " + showId + ".");
    }

    public static ApiException seatsUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "seat-unavailable",
                "One or more of the requested seats are already held or booked.");
    }

    public static ApiException idempotencyKeyInUse() {
        return new ApiException(HttpStatus.CONFLICT, "idempotency-key-in-use",
                "A request with this Idempotency-Key is still being processed. Retry shortly.");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "This Idempotency-Key was already used for a different request.");
    }
}
