package io.github.ayushkzz.seatbooking.notification;

import io.github.ayushkzz.seatbooking.common.events.BookingCancelled;
import io.github.ayushkzz.seatbooking.common.events.BookingConfirmed;
import io.github.ayushkzz.seatbooking.common.events.BookingExpired;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class BookingEventsListener {

    private final NotificationService notifications;
    private final ObjectMapper json;

    BookingEventsListener(NotificationService notifications, ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @KafkaListener(topics = Topics.BOOKING_CONFIRMED)
    void onConfirmed(String payload) {
        notifications.on(json.readValue(payload, BookingConfirmed.class));
    }

    @KafkaListener(topics = Topics.BOOKING_CANCELLED)
    void onCancelled(String payload) {
        notifications.on(json.readValue(payload, BookingCancelled.class));
    }

    @KafkaListener(topics = Topics.BOOKING_EXPIRED)
    void onExpired(String payload) {
        notifications.on(json.readValue(payload, BookingExpired.class));
    }
}
