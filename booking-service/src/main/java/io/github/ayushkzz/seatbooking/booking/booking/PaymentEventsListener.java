package io.github.ayushkzz.seatbooking.booking.booking;

import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.PaymentFailed;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class PaymentEventsListener {

    private final BookingLifecycleService lifecycle;
    private final ObjectMapper json;

    PaymentEventsListener(BookingLifecycleService lifecycle, ObjectMapper json) {
        this.lifecycle = lifecycle;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED)
    void onPaymentCompleted(String payload) {
        lifecycle.onPaymentCompleted(json.readValue(payload, PaymentCompleted.class));
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED)
    void onPaymentFailed(String payload) {
        lifecycle.onPaymentFailed(json.readValue(payload, PaymentFailed.class));
    }
}
