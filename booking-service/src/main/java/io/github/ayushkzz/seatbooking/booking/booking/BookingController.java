package io.github.ayushkzz.seatbooking.booking.booking;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class BookingController {

    private final BookingService bookings;

    BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    record CreateBookingRequest(
            @NotBlank @Size(max = 100) String userId,
            @NotEmpty @Size(max = 10) List<@NotNull @Positive Long> seatIds) {
    }

    record BookingResponse(UUID id, long showId, String userId, BookingStatus status, List<Long> seatIds,
                           long amountCents, Instant createdAt, Instant expiresAt) {

        static BookingResponse of(Booking b) {
            return new BookingResponse(b.getId(), b.getShowId(), b.getUserId(), b.getStatus(), b.getSeatIds(),
                    b.getAmountCents(), b.getCreatedAt(), b.getExpiresAt());
        }
    }

    /**
     * Holds the seats and starts payment. Returns 201 for a new booking, or 200 with the original
     * booking when the request is a retry with the same Idempotency-Key.
     */
    @PostMapping("/shows/{showId}/bookings")
    ResponseEntity<BookingResponse> create(@PathVariable long showId,
                                           @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String key,
                                           @RequestHeader(value = "X-Queue-Ticket", required = false) String queueTicket,
                                           @Valid @RequestBody CreateBookingRequest request) {
        var result = bookings.create(showId, key, request.userId(), request.seatIds(), queueTicket);
        var body = BookingResponse.of(result.booking());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/bookings/" + body.id())).body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/bookings/{bookingId}")
    BookingResponse get(@PathVariable UUID bookingId) {
        return BookingResponse.of(bookings.get(bookingId));
    }
}
