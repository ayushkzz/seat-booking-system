package io.github.ayushkzz.seatbooking.notification;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.common.events.BookingCancelled;
import io.github.ayushkzz.seatbooking.common.events.BookingConfirmed;
import io.github.ayushkzz.seatbooking.common.events.BookingExpired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns booking events into notifications. Events can be delivered more than once, so each
 * notification remembers the event that caused it and a repeated event is ignored. The
 * notification is stored and sent in one transaction: if sending fails, nothing is stored and
 * Kafka redelivers the event.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationSender sender;
    private final MeterRegistry metrics;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, NotificationSender sender, MeterRegistry metrics,
                        Clock clock) {
        this.notifications = notifications;
        this.sender = sender;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void on(BookingConfirmed event) {
        int seats = event.seatIds().size();
        notifyOnce(event.eventId(), event.bookingId(), event.userId(), NotificationType.BOOKING_CONFIRMED,
                String.format(Locale.ROOT, "Your booking is confirmed: %d %s for show %d. Amount paid: %d.%02d.",
                        seats, seats == 1 ? "seat" : "seats", event.showId(),
                        event.amountCents() / 100, event.amountCents() % 100));
    }

    @Transactional
    public void on(BookingCancelled event) {
        notifyOnce(event.eventId(), event.bookingId(), event.userId(), NotificationType.BOOKING_CANCELLED,
                "Your booking was cancelled because the payment failed (" + event.reason()
                        + "). The seats were released and you were not charged.");
    }

    @Transactional
    public void on(BookingExpired event) {
        notifyOnce(event.eventId(), event.bookingId(), event.userId(), NotificationType.BOOKING_EXPIRED,
                "Your booking expired because the payment was not completed in time. The seats were released.");
    }

    @Transactional(readOnly = true)
    public List<Notification> latestFor(String userId) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    private void notifyOnce(UUID eventId, UUID bookingId, String userId, NotificationType type, String message) {
        if (notifications.existsByEventId(eventId)) {
            return;
        }
        Notification notification = notifications.save(
                new Notification(eventId, bookingId, userId, type, message, clock.instant()));
        sender.send(notification);
        metrics.counter("notifications.sent", "type", type.name()).increment();
    }
}
