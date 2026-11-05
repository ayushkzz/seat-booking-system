package io.github.ayushkzz.seatbooking.booking.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases seats of bookings that were not paid before their hold ran out. */
@Component
class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    private static final int BATCH_SIZE = 100;

    private final BookingLifecycleService lifecycle;

    ExpiryJob(BookingLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${booking.expiry-scan-interval}")
    void expireOverdueBookings() {
        try {
            int expired;
            do {
                expired = lifecycle.expireOverdue(BATCH_SIZE);
                if (expired > 0) {
                    log.info("Expired {} unpaid bookings", expired);
                }
            } while (expired == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Expiry scan failed, will retry: {}", e.getMessage());
        }
    }
}
