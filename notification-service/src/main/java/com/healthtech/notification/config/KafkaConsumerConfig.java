package com.healthtech.notification.config;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
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

    private Map<String, Object> baseConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    @Bean
    public ConsumerFactory<String, AppointmentBooked> bookedConsumerFactory() {
        JsonDeserializer<AppointmentBooked> deserializer =
                new JsonDeserializer<>(AppointmentBooked.class);
        deserializer.ignoreTypeHeaders();
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps(),
                new StringDeserializer(),
                deserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentBooked> bookedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, AppointmentBooked> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bookedConsumerFactory());
        return factory;
    }

    @Bean
    public ConsumerFactory<String, AppointmentCancelled> cancelledConsumerFactory() {
        JsonDeserializer<AppointmentCancelled> deserializer =
                new JsonDeserializer<>(AppointmentCancelled.class);
        deserializer.ignoreTypeHeaders();
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps(),
                new StringDeserializer(),
                deserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AppointmentCancelled> cancelledKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, AppointmentCancelled> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cancelledConsumerFactory());
        return factory;
    }
}