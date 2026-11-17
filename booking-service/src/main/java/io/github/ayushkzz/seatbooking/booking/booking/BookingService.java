package io.github.ayushkzz.seatbooking.booking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.booking.api.ApiException;
import io.github.ayushkzz.seatbooking.booking.config.BookingProperties;
import io.github.ayushkzz.seatbooking.booking.hold.IdempotencyGuard;
import io.github.ayushkzz.seatbooking.booking.hold.SeatHoldService;
import io.github.ayushkzz.seatbooking.booking.outbox.OutboxWriter;
import io.github.ayushkzz.seatbooking.booking.show.SeatRepository;
import io.github.ayushkzz.seatbooking.booking.show.SeatStatus;
import io.github.ayushkzz.seatbooking.booking.show.ShowRepository;
import io.github.ayushkzz.seatbooking.booking.waitingroom.WaitingRoomService;
import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates bookings. A request has to pass three independent guards:
 * <ol>
 *   <li>an idempotency lock, so retries of the same request never run concurrently;</li>
 *   <li>an atomic Redis hold on every seat, which rejects most conflicts without touching the database;</li>
 *   <li>a compare-and-set update in PostgreSQL, the source of truth, which still prevents double
 *       booking if a Redis hold is lost.</li>
 * </ol>
 * The booking and its {@link BookingCreated} event are committed in one transaction (outbox pattern).
 */
@Service
public class BookingService {

    /** Redis holds outlive the booking slightly, so the expiry job, not Redis, decides when seats free up. */
    private static final Duration HOLD_GRACE = Duration.ofMinutes(1);

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final SeatHoldService holds;
    private final WaitingRoomService waitingRoom;
    private final IdempotencyGuard idempotency;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final BookingMetrics metrics;
    private final BookingProperties props;
    private final Clock clock;

    BookingService(ShowRepository shows, SeatRepository seats, BookingRepository bookings, SeatHoldService holds,
                   WaitingRoomService waitingRoom, IdempotencyGuard idempotency, OutboxWriter outbox, TransactionTemplate tx,
                   BookingMetrics metrics, BookingProperties props, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.bookings = bookings;
        this.holds = holds;
        this.waitingRoom = waitingRoom;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = tx;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    public record Result(Booking booking, boolean created) {
    }

    /**
     * @param queueTicket the caller's waiting room ticket; only needed for shows with a waiting room
     */
    public Result create(long showId, String idempotencyKey, String userId, List<Long> requestedSeatIds,
                         String queueTicket) {
        List<Long> seatIds = requestedSeatIds.stream().distinct().sorted().toList();
        String lock = idempotency.tryLock(userId, idempotencyKey).orElseThrow(ApiException::idempotencyKeyInUse);
        try {
            var previous = bookings.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (previous.isPresent()) {
                return replay(previous.get(), showId, seatIds);
            }
            if (!shows.existsById(showId)) {
                throw ApiException.showNotFound(showId);
            }
            if (seats.countByShowIdAndIdIn(showId, seatIds) != seatIds.size()) {
                throw ApiException.seatsNotFound(showId);
            }
            // Shows with a waiting room only take bookings from users whose turn it is. A booking
            // uses up the turn, so the next person in the queue can come in.
            Optional<UUID> admission = waitingRoom.requireAdmission(showId, userId, queueTicket);

            UUID bookingId = UUID.randomUUID();
            if (!holds.hold(showId, seatIds, bookingId, props.holdTtl().plus(HOLD_GRACE))) {
                metrics.redisConflict();
                throw ApiException.seatsUnavailable();
            }
            try {
                Booking booking = tx.execute(status -> persist(bookingId, showId, userId, idempotencyKey, seatIds));
                metrics.created();
                admission.ifPresent(ticket -> waitingRoom.endSession(showId, ticket));
                return new Result(booking, true);
            } catch (RuntimeException e) {
                holds.release(showId, seatIds, bookingId);
                throw e;
            }
        } finally {
            idempotency.unlock(userId, idempotencyKey, lock);
        }
    }

    private Booking persist(UUID bookingId, long showId, String userId, String idempotencyKey, List<Long> seatIds) {
        if (seats.transition(showId, seatIds, SeatStatus.AVAILABLE, SeatStatus.HELD) != seatIds.size()) {
            metrics.databaseConflict();
            throw ApiException.seatsUnavailable(); // rolls back any seats this transaction did take
        }
        long amount = seats.sumPrice(showId, seatIds);
        Instant now = clock.instant();
        Booking booking = Booking.pending(bookingId, showId, userId, idempotencyKey, seatIds, amount,
                now, now.plus(props.holdTtl()));
        bookings.save(booking);
        outbox.enqueue(Topics.BOOKING_CREATED, bookingId, new BookingCreated(UUID.randomUUID(), bookingId,
                showId, userId, seatIds, amount, now, booking.getExpiresAt()));
        return booking;
    }

    /** Same key and same request: return the original booking. Same key, different request: reject. */
    private Result replay(Booking booking, long showId, List<Long> seatIds) {
        if (booking.getShowId() != showId || !new HashSet<>(booking.getSeatIds()).equals(new HashSet<>(seatIds))) {
            throw ApiException.idempotencyKeyReused();
        }
        return new Result(booking, false);
    }

    public Booking get(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(() -> ApiException.bookingNotFound(bookingId));
    }
}
