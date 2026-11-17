package io.github.ayushkzz.seatbooking.booking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdTtl            how long seats stay held while the customer pays
 * @param expiryScanInterval how often unpaid bookings past their hold are expired
 * @param outboxPollInterval how often the outbox relay publishes pending events
 * @param seatMapCacheTtl    how long a rendered seat map is served from the cache
 * @param waitingRoom        settings for shows that queue their visitors
 */
@ConfigurationProperties("booking")
public record BookingProperties(Duration holdTtl, Duration expiryScanInterval, Duration outboxPollInterval,
                                Duration seatMapCacheTtl, WaitingRoom waitingRoom) {

    /**
     * @param sessionTtl    how long an admitted user has to make a booking
     * @param heartbeatTtl  a waiting user who has not checked their place for this long is dropped
     * @param admitInterval how often free places are given to the users at the front of the queue
     */
    public record WaitingRoom(Duration sessionTtl, Duration heartbeatTtl, Duration admitInterval) {
    }
}
