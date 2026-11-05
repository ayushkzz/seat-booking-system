package io.github.ayushkzz.seatbooking.booking.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    long countByShowId(long showId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(UUID id);

    /** SKIP LOCKED lets several instances expire bookings in parallel without blocking each other. */
    @Query(value = """
            select * from bookings
            where status = 'PENDING_PAYMENT' and expires_at < :now
            order by expires_at
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<Booking> lockExpired(Instant now, int limit);
}
