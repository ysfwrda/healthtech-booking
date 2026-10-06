package com.healthtech.appointment.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

// A dedicated producer for the outbox relay. The two typed, JsonSerializer-based producer
// KafkaTemplate beans AppointmentService used to depend on (for AppointmentBooked and
// AppointmentCancelled) are removed entirely by this change, so unlike doctor-service there is no
// other producer left needing the application's default autoconfigured KafkaTemplate. The relay
// publishes payloads that are already serialized JSON strings, so it needs a plain String value
// serializer rather than the global spring.kafka.producer.value-serializer.
@Configuration
public class OutboxKafkaConfig {

    @Bean
    public ProducerFactory<String, String> outboxProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${outbox.relay.max-block-ms:15000}") int maxBlockMs,
            @Value("${outbox.relay.request-timeout-ms:30000}") int requestTimeoutMs,
            @Value("${outbox.relay.delivery-timeout-ms:120000}") int deliveryTimeoutMs) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // The claim transaction holds row locks across the publish, so the broker wait must be
        // bounded: an unbounded max.block.ms would turn a broker hang into indefinitely held locks.
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
