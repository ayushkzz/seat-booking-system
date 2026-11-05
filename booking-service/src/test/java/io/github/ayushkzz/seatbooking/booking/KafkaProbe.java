package io.github.ayushkzz.seatbooking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning and waits for messages with a given key. */
final class KafkaProbe implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    KafkaProbe(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
    }

    /** Returns the value of the first message with this key, or fails after the timeout. */
    String awaitValue(String key, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : seen) {
                if (key.equals(record.key())) {
                    return record.value();
                }
            }
            consumer.poll(Duration.ofMillis(250)).forEach(seen::add);
        }
        throw new AssertionError("No message with key " + key + " within " + timeout);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
