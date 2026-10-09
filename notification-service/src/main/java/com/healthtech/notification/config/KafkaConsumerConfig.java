package com.healthtech.notification.config;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.event.AppointmentChanged;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> baseConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    // Each appointment event type gets its own typed consumer and listener factory, built the
    // same way by these helpers; the per-type beans below are one line each.
    private <T> ConsumerFactory<String, T> consumerFactory(Class<T> eventType) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(eventType);
        deserializer.ignoreTypeHeaders();
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps(),
                new StringDeserializer(),
                deserializer
        );
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(ConsumerFactory<String, T> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, AppointmentBooked> bookedConsumerFactory() {
        return consumerFactory(AppointmentBooked.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentBooked> bookedKafkaListenerContainerFactory() {
        return listenerFactory(bookedConsumerFactory());
    }

    @Bean
    public ConsumerFactory<String, AppointmentCancelled> cancelledConsumerFactory() {
        return consumerFactory(AppointmentCancelled.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentCancelled> cancelledKafkaListenerContainerFactory() {
        return listenerFactory(cancelledConsumerFactory());
    }

    @Bean
    public ConsumerFactory<String, AppointmentChanged> changedConsumerFactory() {
        return consumerFactory(AppointmentChanged.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentChanged> changedKafkaListenerContainerFactory() {
        return listenerFactory(changedConsumerFactory());
    }
}
