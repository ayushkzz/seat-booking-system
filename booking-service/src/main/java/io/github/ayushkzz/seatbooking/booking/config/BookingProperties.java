package io.github.ayushkzz.seatbooking.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdTtl            how long seats stay held while the customer pays
 * @param expiryScanInterval how often unpaid bookings past their hold are expired
 * @param outboxPollInterval how often the outbox relay publishes pending events
 * @param seatMapCacheTtl    how long a rendered seat map is served from the cache
 */
@ConfigurationProperties("booking")
public record BookingProperties(Duration holdTtl, Duration expiryScanInterval, Duration outboxPollInterval,
                                Duration seatMapCacheTtl) {
}
