package io.github.ayushkzz.seatbooking.payment;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import io.github.ayushkzz.seatbooking.common.events.PaymentCompleted;
import io.github.ayushkzz.seatbooking.common.events.PaymentFailed;
import io.github.ayushkzz.seatbooking.common.events.RefundRequested;
import io.github.ayushkzz.seatbooking.common.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Charges each booking exactly once. booking.created can be delivered more than once (at-least-once
 * messaging), so the stored payment (unique per booking) is the source of truth: a redelivered
 * event re-publishes the stored result instead of charging again. The result is only published
 * after the payment row is committed; if publishing fails the message is redelivered and the stored
 * result is published then.
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentSimulator simulator;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final Clock clock;

    PaymentService(PaymentRepository payments, PaymentSimulator simulator, KafkaTemplate<String, String> kafka,
                   ObjectMapper json, MeterRegistry metrics, Clock clock) {
        this.payments = payments;
        this.simulator = simulator;
        this.kafka = kafka;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
    }

    void handle(BookingCreated booking) {
        Payment payment = payments.findByBookingId(booking.bookingId()).orElseGet(() -> charge(booking));
        publishResult(payment);
    }

    private Payment charge(BookingCreated booking) {
        PaymentSimulator.Outcome outcome = simulator.charge(booking);
        try {
            Payment payment = payments.saveAndFlush(
                    Payment.of(booking.bookingId(), booking.amountCents(), outcome, clock.instant()));
            metrics.counter("payments.processed", "status", payment.getStatus().name()).increment();
            return payment;
        } catch (DataIntegrityViolationException alreadyCharged) {
            return payments.findByBookingId(booking.bookingId()).orElseThrow(() -> alreadyCharged);
        }
    }

    private void publishResult(Payment payment) {
        switch (payment.getStatus()) {
            case SUCCEEDED -> send(Topics.PAYMENT_COMPLETED, payment.getBookingId(), new PaymentCompleted(
                    UUID.randomUUID(), payment.getBookingId(), payment.getId(), payment.getAmountCents(),
                    payment.getUpdatedAt()));
            case FAILED -> send(Topics.PAYMENT_FAILED, payment.getBookingId(), new PaymentFailed(
                    UUID.randomUUID(), payment.getBookingId(), payment.getFailureReason(), payment.getUpdatedAt()));
            case REFUNDED -> log.debug("Booking {} was already refunded, nothing to publish", payment.getBookingId());
        }
    }

    @Transactional
    void refund(RefundRequested request) {
        payments.findByBookingId(request.bookingId()).ifPresent(payment -> {
            if (payment.refund(clock.instant())) {
                metrics.counter("payments.refunded").increment();
                log.info("Refunded payment {} for booking {}: {}", payment.getId(), payment.getBookingId(),
                        request.reason());
            }
        });
    }

    private void send(String topic, UUID key, Object event) {
        try {
            kafka.send(topic, key.toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish " + topic + " for booking " + key, e);
        }
    }
}
