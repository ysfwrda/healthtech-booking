package com.healthtech.doctor.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.CreateDoctorRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DoctorIntegrationTest.TestSecurityConfig.class)
public class DoctorIntegrationTest {

    static final KeyPair KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @MockitoBean
    KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    SpecialtyRepository specialtyRepository;
    @Autowired
    TestRestTemplate restTemplate;

    private HttpHeaders authHeaders() {
        String token = TestJwtFactory.anyValidToken((RSAPrivateKey) KEY_PAIR.getPrivate());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private CreateDoctorRequest.CreateDoctorRequestBuilder validRequestBuilder(String email) {
        Specialty specialty = specialtyRepository.findByName("General Practice").orElseThrow();

        AddressDto address = AddressDto.builder()
                .street("Main St")
                .houseNumber("1")
                .postalCode("12345")
                .city("Berlin")
                .country("Germany")
                .build();

        OpeningHoursDto openingHours = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        return CreateDoctorRequest.builder()
                .firstName("John")
                .lastName("Smith")
                .email(email)
                .phoneNumber("+491234567")
                .address(address)
                .specialtyIds(Set.of(specialty.getId()))
                .openingHours(Set.of(openingHours))
                .languages(Set.of(Language.ENGLISH));
    }

    @Test
    void createDoctor_duplicateEmail_returns409AndSingleRow() throws Exception {
        CreateDoctorRequest first = validRequestBuilder("john.smith@example.com").build();
        ResponseEntity<DoctorResponse> firstResponse = restTemplate.exchange(
                "/api/doctors", HttpMethod.POST, new HttpEntity<>(first, authHeaders()), DoctorResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        CreateDoctorRequest duplicate = validRequestBuilder("john.smith@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/doctors", HttpMethod.POST, new HttpEntity<>(duplicate, authHeaders()), String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Email Already Registered");
        assertThat(problem.get("detail").asText()).contains("john.smith@example.com");

        assertThat(doctorRepository.findAll().stream().filter(d -> d.getEmail().equals("john.smith@example.com")).count()).isEqualTo(1);
    }

    @Test
    void createDoctor_publishesDoctorRegisteredEvent() {
        CreateDoctorRequest request = validRequestBuilder("event.doctor@example.com").build();

        ResponseEntity<DoctorResponse> response = restTemplate.exchange(
                "/api/doctors", HttpMethod.POST, new HttpEntity<>(request, authHeaders()), DoctorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID doctorId = response.getBody().getId();

        var captor = org.mockito.ArgumentCaptor.forClass(DoctorRegistered.class);
        verify(kafkaTemplate).send(eq("doctor.registered"), captor.capture());
        DoctorRegistered event = captor.getValue();

        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getFirstName()).isEqualTo("John");
        assertThat(event.getLastName()).isEqualTo("Smith");
        assertThat(event.getOpeningHours()).hasSize(1);
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                    .build();
        }
    }
}
