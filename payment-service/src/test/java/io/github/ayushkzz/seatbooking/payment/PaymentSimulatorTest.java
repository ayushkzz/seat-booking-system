package io.github.ayushkzz.seatbooking.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.ayushkzz.seatbooking.common.events.BookingCreated;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

    private static BookingCreated booking(String userId) {
        return new BookingCreated(UUID.randomUUID(), UUID.randomUUID(), 1L, userId, List.of(1L), 100,
                Instant.now(), Instant.now());
    }

    private static PaymentSimulator simulator(double failureRate) {
        return new PaymentSimulator(new PaymentSimulator.Settings(failureRate, Duration.ZERO, Duration.ZERO, "decline"));
    }

    @Test
    void approvesEverythingWhenFailureRateIsZero() {
        var simulator = simulator(0);
        for (int i = 0; i < 100; i++) {
            assertThat(simulator.charge(booking("user-" + i)).approved()).isTrue();
        }
    }

    @Test
    void alwaysDeclinesUsersWithTheDeclinePrefix() {
        var outcome = simulator(0).charge(booking("decline-someone"));
        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.declineReason()).isEqualTo("Card declined");
    }

    @Test
    void declinesEverythingWhenFailureRateIsOne() {
        assertThat(simulator(1).charge(booking("user")).approved()).isFalse();
    }
}
