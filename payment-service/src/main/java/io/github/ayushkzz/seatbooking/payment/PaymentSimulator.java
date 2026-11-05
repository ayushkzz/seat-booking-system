package io.github.ayushkzz.seatbooking.payment;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Stands in for a real payment provider: adds latency and declines a configurable share of charges. */
@Component
class PaymentSimulator {

    @ConfigurationProperties("payment.simulation")
    record Settings(double failureRate, Duration latencyMin, Duration latencyMax, String declineUserPrefix) {
    }

    record Outcome(boolean approved, String declineReason) {

        static Outcome approve() {
            return new Outcome(true, null);
        }

        static Outcome decline(String reason) {
            return new Outcome(false, reason);
        }
    }

    private final Settings settings;

    PaymentSimulator(Settings settings) {
        this.settings = settings;
    }

    Outcome charge(BookingCreated booking) {
        simulateLatency();
        if (booking.userId().startsWith(settings.declineUserPrefix())) {
            return Outcome.decline("Card declined");
        }
        if (ThreadLocalRandom.current().nextDouble() < settings.failureRate()) {
            return Outcome.decline("Insufficient funds");
        }
        return Outcome.approve();
    }

    private void simulateLatency() {
        long min = settings.latencyMin().toMillis();
        long max = Math.max(min, settings.latencyMax().toMillis());
        long millis = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
