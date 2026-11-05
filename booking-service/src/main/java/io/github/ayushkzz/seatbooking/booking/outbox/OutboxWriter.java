package io.github.ayushkzz.seatbooking.booking.outbox;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Records an event in the outbox as part of the caller's transaction. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;

    OutboxWriter(OutboxRepository outbox, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, UUID key, Object event) {
        outbox.save(new OutboxEvent(topic, key.toString(), event.getClass().getSimpleName(),
                json.writeValueAsString(event), clock.instant()));
    }
}
