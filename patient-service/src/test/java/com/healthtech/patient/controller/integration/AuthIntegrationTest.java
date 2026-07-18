package com.healthtech.patient.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.event.PatientRegistered;
import com.healthtech.patient.repository.PatientRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @MockitoBean
    KafkaTemplate<String, PatientRegistered> kafkaTemplate;

    @Autowired
    PatientRepository patientRepository;
    @Autowired
    TestRestTemplate restTemplate;

    private RegisterRequest.RegisterRequestBuilder validRequestBuilder(String username, String email) {
        return RegisterRequest.builder()
                .firstName("Jane")
                .lastName("Doe")
                .username(username)
                .password("Sup3rSecret!")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .email(email)
                .insuranceType(InsuranceType.STATUTORY);
    }

    @Test
    void register_duplicateUsername_returns409AndSingleRow() throws Exception {
        RegisterRequest first = validRequestBuilder("janedoe", "jane.doe@example.com").build();
        ResponseEntity<AuthResponse> firstResponse = restTemplate.postForEntity("/api/auth/register", first, AuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        RegisterRequest duplicate = validRequestBuilder("janedoe", "different.email@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity("/api/auth/register", duplicate, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Username Already Taken");
        assertThat(problem.get("detail").asText()).isEqualTo("janedoe");

        assertThat(patientRepository.findAll().stream().filter(p -> p.getUsername().equals("janedoe")).count()).isEqualTo(1);
    }

    @Test
    void register_duplicateEmail_returns409AndSingleRow() throws Exception {
        RegisterRequest first = validRequestBuilder("firstuser", "shared@example.com").build();
        ResponseEntity<AuthResponse> firstResponse = restTemplate.postForEntity("/api/auth/register", first, AuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        RegisterRequest duplicate = validRequestBuilder("seconduser", "shared@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity("/api/auth/register", duplicate, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Email Already Registered");
        assertThat(problem.get("detail").asText()).isEqualTo("shared@example.com");

        assertThat(patientRepository.findAll().stream().filter(p -> p.getEmail().equals("shared@example.com")).count()).isEqualTo(1);
    }

    @Test
    void register_publishesPatientRegisteredEvent() {
        RegisterRequest request = validRequestBuilder("eventuser", "event.user@example.com").build();

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var patient = patientRepository.findByUsername("eventuser").orElseThrow();

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, PatientRegistered> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("patient.registered");
        PatientRegistered event = record.value();

        assertThat(event.getPatientId()).isEqualTo(patient.getId());
        assertThat(event.getFirstName()).isEqualTo("Jane");
        assertThat(event.getLastName()).isEqualTo("Doe");
        assertThat(event.getEmail()).isEqualTo("event.user@example.com");
    }
}
