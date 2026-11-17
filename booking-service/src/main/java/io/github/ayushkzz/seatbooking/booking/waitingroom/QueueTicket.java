package io.github.ayushkzz.seatbooking.booking.waitingroom;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's place in a show's waiting room.
 *
 * @param position  1 for the next user to be admitted; null once admitted
 * @param expiresAt when an admitted user's turn ends; null while waiting
 */
public record QueueTicket(UUID ticketId, Status status, Long position, Instant expiresAt) {

    public enum Status {
        WAITING,
        ADMITTED
    }
}
