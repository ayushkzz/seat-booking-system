package io.github.ayushkzz.seatbooking.booking.show;

/** One seat as shown in the seat map. */
public record SeatView(long id, String label, SeatStatus status, long priceCents) {
}
