package io.github.ayushkzz.seatbooking.booking.outbox;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Rows are locked with SKIP LOCKED, so several instances can relay
 * in parallel, and only marked as published after Kafka acknowledges them. If the send fails the
 * transaction rolls back and the rows are retried on the next tick (at-least-once delivery;
 * consumers are idempotent).
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 200;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${booking.outbox-poll-interval}")
    void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent != null && sent == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed, will retry: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outbox.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        CompletableFuture<?>[] acks = batch.stream()
                .map(e -> kafka.send(e.getTopic(), e.getMessageKey(), e.getPayload()))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(acks).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox events", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge outbox batch", e);
        }
        var now = clock.instant();
        batch.forEach(e -> e.markPublished(now));
        return batch.size();
    }
}
