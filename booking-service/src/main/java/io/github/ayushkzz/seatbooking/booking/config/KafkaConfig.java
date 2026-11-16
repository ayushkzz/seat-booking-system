package io.github.ayushkzz.seatbooking.booking.config;

import io.github.ayushkzz.seatbooking.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    @Bean
    KafkaAdmin.NewTopics topics(@Value("${kafka.topic.partitions:3}") int partitions,
                                @Value("${kafka.topic.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * Kafka consumers get ordinary platform threads even though virtual threads are enabled for
     * web requests. A consumer spends its life blocked in poll(), which pins the carrier thread
     * of a virtual thread; a dozen consumers would pin every carrier and stall the whole service.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> kafkaConsumerThreads() {
        return container -> container.getContainerProperties()
                .setListenerTaskExecutor(new SimpleAsyncTaskExecutor("kafka-consumer-"));
    }

    /** Retry a failing message 3 times, then park it on "<topic>-dlt" instead of blocking the partition. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(500L, 3));
    }
}
