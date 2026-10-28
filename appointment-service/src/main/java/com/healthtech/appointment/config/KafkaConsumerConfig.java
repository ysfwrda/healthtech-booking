package com.healthtech.appointment.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.healthtech.appointment.event.DoctorAvailabilityUpdated;
import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.PatientRegistered;
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

    private <T> ConsumerFactory<String, T> factoryFor(Class<T> type) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // A producer may add a field before this service is upgraded; without this the record never deserializes.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, mapper);
        deserializer.ignoreTypeHeaders();
        deserializer.setRemoveTypeHeaders(true);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers); // injected, not hardcoded
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "appointment-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory <String, DoctorRegistered> doctorRegisteredKafkaListenerFactory() {
         ConcurrentKafkaListenerContainerFactory<String, DoctorRegistered> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(factoryFor(DoctorRegistered.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DoctorAvailabilityUpdated> doctorAvailabilityKafkaListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, DoctorAvailabilityUpdated> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(factoryFor(DoctorAvailabilityUpdated.class));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PatientRegistered> patientRegisteredKafkaListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PatientRegistered> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(factoryFor(PatientRegistered.class));
        return factory;
    }
}