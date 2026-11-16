package io.github.ayushkzz.seatbooking.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in for an email or SMS provider: writes the message to the log. */
@Component
class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("To {}: {}", notification.getUserId(), notification.getMessage());
    }
}
