package io.github.ayushkzz.seatbooking.booking.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.booking.hold.SeatHoldService;
import io.github.ayushkzz.seatbooking.booking.outbox.OutboxWriter;
import io.github.ayushkzz.seatbooking.booking.show.SeatRepository;
import io.github.ayushkzz.seatbooking.booking.show.SeatStatus;
import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.PaymentFailed;
import io.github.ayushkzz.seatbooking.common.events.RefundRequested;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The booking saga after the seats are held. Every transition starts from PENDING_PAYMENT under a
 * row lock, which makes the handlers idempotent: duplicate or late events find the booking in a
 * final state and are ignored, except a payment that arrives after the booking expired or was
 * cancelled, which is compensated with a refund request.
 */
@Service
public class BookingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BookingLifecycleService.class);

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final SeatHoldService holds;
    private final OutboxWriter outbox;
    private final BookingMetrics metrics;
    private final Clock clock;

    BookingLifecycleService(BookingRepository bookings, SeatRepository seats, SeatHoldService holds,
                            OutboxWriter outbox, BookingMetrics metrics, Clock clock) {
        this.bookings = bookings;
        this.seats = seats;
        this.holds = holds;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void onPaymentCompleted(PaymentCompleted event) {
        Booking booking = bookings.lockById(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("Payment {} for unknown booking {}", event.paymentId(), event.bookingId());
            return;
        }
        switch (booking.getStatus()) {
            case PENDING_PAYMENT -> {
                moveSeats(booking, SeatStatus.HELD, SeatStatus.BOOKED);
                booking.confirm(clock.instant());
                releaseHoldsAfterCommit(booking);
                metrics.confirmed();
            }
            case EXPIRED, CANCELLED -> {
                log.info("Payment arrived for {} booking {}, requesting refund", booking.getStatus(), booking.getId());
                outbox.enqueue(Topics.BOOKING_REFUND_REQUESTED, booking.getId(), new RefundRequested(
                        UUID.randomUUID(), booking.getId(), "Booking was " + booking.getStatus(), clock.instant()));
            }
            case CONFIRMED -> log.debug("Duplicate payment confirmation for booking {}", booking.getId());
        }
    }

    @Transactional
    public void onPaymentFailed(PaymentFailed event) {
        Booking booking = bookings.lockById(event.bookingId()).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            return;
        }
        moveSeats(booking, SeatStatus.HELD, SeatStatus.AVAILABLE);
        booking.cancel(clock.instant());
        releaseHoldsAfterCommit(booking);
        metrics.cancelled();
        log.info("Booking {} cancelled: {}", booking.getId(), event.reason());
    }

    /** Expires up to {@code limit} unpaid bookings whose hold has run out. Returns how many were expired. */
    @Transactional
    public int expireOverdue(int limit) {
        Instant now = clock.instant();
        List<Booking> overdue = bookings.lockExpired(now, limit);
        for (Booking booking : overdue) {
            moveSeats(booking, SeatStatus.HELD, SeatStatus.AVAILABLE);
            booking.expire(now);
            releaseHoldsAfterCommit(booking);
            metrics.expired();
        }
        return overdue.size();
    }

    private void moveSeats(Booking booking, SeatStatus from, SeatStatus to) {
        int moved = seats.transition(booking.getShowId(), booking.getSeatIds(), from, to);
        if (moved != booking.getSeatIds().size()) {
            log.error("Booking {}: expected {} seats in {}, moved {}", booking.getId(),
                    booking.getSeatIds().size(), from, moved);
        }
    }

    private void releaseHoldsAfterCommit(Booking booking) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                holds.release(booking.getShowId(), booking.getSeatIds(), booking.getId());
            }
        });
    }
}
