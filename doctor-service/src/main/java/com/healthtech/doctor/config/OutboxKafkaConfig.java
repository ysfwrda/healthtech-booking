package com.healthtech.doctor.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

// A dedicated producer for the outbox relay, separate from the application's typed,
// JsonSerializer-based KafkaTemplate beans (including DoctorSeeder's, which is out of scope for
// the outbox and keeps publishing directly). The relay publishes payloads that are already
// serialized JSON strings, so it needs a plain String value serializer. Changing the global
// spring.kafka.producer.value-serializer instead would break the seeder's typed template.
//
// Declaring any KafkaTemplate bean here disables Spring Boot's own autoconfigured one: its
// @ConditionalOnMissingBean(KafkaTemplate.class) matches on the raw KafkaTemplate type, ignoring
// generics, so it backs off the moment outboxKafkaTemplate exists even though the two are
// completely different types. DoctorSeeder still needs that default, JsonSerializer-based
// KafkaTemplate<String, DoctorRegistered>, so this class rebuilds it explicitly from Spring
// Boot's own KafkaProperties rather than leaving DoctorSeeder's dependency unsatisfied.
@Configuration
public class OutboxKafkaConfig {

    // Wildcards, not <Object, Object>: Spring's generics-aware autowiring treats an unbounded
    // wildcard as compatible with any concrete request (this is exactly the signature Spring
    // Boot's own KafkaAutoConfiguration.kafkaTemplate() declares) so this still satisfies
    // DoctorSeeder's KafkaTemplate<String, DoctorRegistered> injection point. A concrete
    // <Object, Object> declaration would not, since generics are otherwise invariant.
    @Bean
    public ProducerFactory<?, ?> kafkaProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
    }

    @Bean
    public KafkaTemplate<?, ?> kafkaTemplate(ProducerFactory<Object, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

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
