package io.github.ayushkzz.seatbooking.booking.waitingroom;

import java.util.UUID;

import io.github.ayushkzz.seatbooking.booking.api.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows/{showId}/queue")
class WaitingRoomController {

    private final WaitingRoomService waitingRoom;

    WaitingRoomController(WaitingRoomService waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    record JoinRequest(@NotBlank @Size(max = 100) String userId) {
    }

    /** Joins the show's waiting room. Joining again returns the same place in the queue. */
    @PostMapping
    QueueTicket join(@PathVariable long showId, @Valid @RequestBody JoinRequest request) {
        return waitingRoom.join(showId, request.userId());
    }

    /**
     * The ticket's place in the queue. Clients poll this every few seconds: once the status is
     * ADMITTED, the ticket id goes in the X-Queue-Ticket header when booking.
     */
    @GetMapping("/{ticketId}")
    QueueTicket status(@PathVariable long showId, @PathVariable UUID ticketId) {
        return waitingRoom.status(showId, ticketId).orElseThrow(ApiException::queueTicketNotFound);
    }

    @DeleteMapping("/{ticketId}")
    ResponseEntity<Void> leave(@PathVariable long showId, @PathVariable UUID ticketId) {
        waitingRoom.leave(showId, ticketId);
        return ResponseEntity.noContent().build();
    }
}
