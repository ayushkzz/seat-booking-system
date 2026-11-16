package io.github.ayushkzz.seatbooking.booking.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByPublishedAtIsNull();

    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by created_at
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(int limit);
}
