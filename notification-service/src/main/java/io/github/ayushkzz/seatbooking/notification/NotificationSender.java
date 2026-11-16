package io.github.ayushkzz.seatbooking.notification;

/** Delivers a notification to the customer. Swap the implementation to send real email, SMS or push. */
public interface NotificationSender {

    void send(Notification notification);
}
