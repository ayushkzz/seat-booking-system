package io.github.ayushkzz.seatbooking.payment;

import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import io.github.ayushkzz.seatbooking.common.events.RefundRequested;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class BookingEventsListener {

    private final PaymentService payments;
    private final ObjectMapper json;

    BookingEventsListener(PaymentService payments, ObjectMapper json) {
        this.payments = payments;
        this.json = json;
    }

    @KafkaListener(topics = Topics.BOOKING_CREATED)
    void onBookingCreated(String payload) {
        payments.handle(json.readValue(payload, BookingCreated.class));
    }

    @KafkaListener(topics = Topics.BOOKING_REFUND_REQUESTED)
    void onRefundRequested(String payload) {
        payments.refund(json.readValue(payload, RefundRequested.class));
    }
}
