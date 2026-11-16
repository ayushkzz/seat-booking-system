package io.github.ayushkzz.seatbooking.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventId(UUID eventId);

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(String userId);

    long countByBookingId(UUID bookingId);
}
