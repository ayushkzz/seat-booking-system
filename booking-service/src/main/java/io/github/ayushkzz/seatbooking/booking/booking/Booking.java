package io.github.ayushkzz.seatbooking.booking.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "bookings")
public class Booking implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long showId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @Column(name = "seat_id", nullable = false)
    private Set<Long> seatIds = new HashSet<>();

    @Transient
    private boolean isNew;

    protected Booking() {
    }

    static Booking pending(UUID id, long showId, String userId, String idempotencyKey,
                           Collection<Long> seatIds, long amountCents, Instant now, Instant expiresAt) {
        Booking b = new Booking();
        b.id = id;
        b.showId = showId;
        b.userId = userId;
        b.idempotencyKey = idempotencyKey;
        b.status = BookingStatus.PENDING_PAYMENT;
        b.amountCents = amountCents;
        b.createdAt = now;
        b.updatedAt = now;
        b.expiresAt = expiresAt;
        b.seatIds.addAll(seatIds);
        b.isNew = true;
        return b;
    }

    void confirm(Instant now) {
        moveTo(BookingStatus.CONFIRMED, now);
    }

    void cancel(Instant now) {
        moveTo(BookingStatus.CANCELLED, now);
    }

    void expire(Instant now) {
        moveTo(BookingStatus.EXPIRED, now);
    }

    private void moveTo(BookingStatus next, Instant now) {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Booking " + id + " is " + status + ", cannot move to " + next);
        }
        status = next;
        updatedAt = now;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public long getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<Long> getSeatIds() {
        return seatIds.stream().sorted().toList();
    }
}
