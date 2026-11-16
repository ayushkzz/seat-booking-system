package io.github.ayushkzz.seatbooking.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    record NotificationResponse(UUID id, UUID bookingId, NotificationType type, String message, Instant createdAt) {
    }

    /** The 50 most recent notifications for a user, newest first. */
    @GetMapping("/api/users/{userId}/notifications")
    List<NotificationResponse> forUser(@PathVariable String userId) {
        return notifications.latestFor(userId).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getBookingId(), n.getType(), n.getMessage(),
                        n.getCreatedAt()))
                .toList();
    }
}
