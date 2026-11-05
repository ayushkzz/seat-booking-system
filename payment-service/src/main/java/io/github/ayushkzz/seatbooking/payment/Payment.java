package io.github.ayushkzz.seatbooking.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "payments")
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID bookingId;

    @Column(nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected Payment() {
    }

    static Payment of(UUID bookingId, long amountCents, PaymentSimulator.Outcome outcome, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.bookingId = bookingId;
        p.amountCents = amountCents;
        p.status = outcome.approved() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        p.failureReason = outcome.declineReason();
        p.createdAt = now;
        p.updatedAt = now;
        p.isNew = true;
        return p;
    }

    /** Returns false if there was nothing to refund (already refunded, or the charge had failed). */
    boolean refund(Instant now) {
        if (status != PaymentStatus.SUCCEEDED) {
            return false;
        }
        status = PaymentStatus.REFUNDED;
        updatedAt = now;
        return true;
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

    public UUID getBookingId() {
        return bookingId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
