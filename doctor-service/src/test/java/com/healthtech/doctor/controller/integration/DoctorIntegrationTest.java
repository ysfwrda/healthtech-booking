package com.healthtech.doctor.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorLoginRequest;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DoctorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    // DoctorSeeder also sends through this mock at context startup; each test clears that
    // (and any prior test's) invocation history first so assertions only see their own send.
    @MockitoBean
    KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    SpecialtyRepository specialtyRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void resetKafkaMock() {
        clearInvocations(kafkaTemplate);
    }

    private DoctorRegistrationRequest.DoctorRegistrationRequestBuilder validRequestBuilder(String email) {
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

        return DoctorRegistrationRequest.builder()
                .firstName("John")
                .lastName("Smith")
                .email(email)
                .password("secret123")
                .phoneNumber("+491234567")
                .address(address)
                .specialtyIds(Set.of(specialty.getId()))
                .openingHours(Set.of(openingHours))
                .languages(Set.of(Language.ENGLISH));
    }

    @Test
    void register_validRequest_returns201WithTokenAndId() {
        DoctorRegistrationRequest request = validRequestBuilder("john.smith.register@example.com").build();

        ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                "/api/doctors/register", request, DoctorAuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getEmail()).isEqualTo("john.smith.register@example.com");
        assertThat(response.getBody().getToken()).isNotBlank();
        assertThat(response.getBody().getExpiresIn()).isPositive();
        assertThat(doctorRepository.findByEmail("john.smith.register@example.com")).isPresent();
    }

    @Test
    void register_duplicateEmail_returns409AndSingleRow() throws Exception {
        DoctorRegistrationRequest first = validRequestBuilder("john.smith.dup@example.com").build();
        ResponseEntity<DoctorAuthResponse> firstResponse = restTemplate.postForEntity(
                "/api/doctors/register", first, DoctorAuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        DoctorRegistrationRequest duplicate = validRequestBuilder("john.smith.dup@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/doctors/register", duplicate, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Email Already Registered");
        assertThat(problem.get("detail").asText()).contains("john.smith.dup@example.com");

        assertThat(doctorRepository.findAll().stream()
                .filter(d -> d.getEmail().equals("john.smith.dup@example.com")).count()).isEqualTo(1);
    }

    @Test
    void register_publishesDoctorRegisteredEvent() {
        DoctorRegistrationRequest request = validRequestBuilder("event.doctor@example.com").build();

        ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                "/api/doctors/register", request, DoctorAuthResponse.class);
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

    @Test
    void register_duplicateOpeningHoursBlock_returns400() throws Exception {
        // OpeningHoursDto has no equals()/hashCode(), so submitting the same block twice
        // produces two distinct Set elements. The overlap check (a range trivially overlaps
        // itself) is what actually rejects this today -- pinned here so that adding DTO
        // equality later doesn't silently flip this case to a 201 with nothing failing.
        OpeningHoursDto block = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();
        OpeningHoursDto sameBlockAgain = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        DoctorRegistrationRequest request = validRequestBuilder("duplicate.block.doctor@example.com")
                .openingHours(new LinkedHashSet<>(Set.of(block, sameBlockAgain)))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/doctors/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("errors").get("openingHours").asText()).containsIgnoringCase("overlap");
        assertThat(doctorRepository.findByEmail("duplicate.block.doctor@example.com")).isEmpty();
    }

    @Test
    void register_overlappingOpeningHours_returns400WithOverlapError() throws Exception {
        OpeningHoursDto first = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(13, 0))
                .build();
        OpeningHoursDto overlapping = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        DoctorRegistrationRequest request = validRequestBuilder("overlap.doctor@example.com")
                .openingHours(new LinkedHashSet<>(Set.of(first, overlapping)))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/doctors/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("title").asText()).isEqualTo("Validation Error");
        assertThat(problem.get("errors").get("openingHours").asText()).containsIgnoringCase("overlap");
        assertThat(doctorRepository.findByEmail("overlap.doctor@example.com")).isEmpty();
    }

    @Test
    void register_backToBackOpeningHours_returns201() {
        OpeningHoursDto morning = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .build();
        OpeningHoursDto afternoon = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(16, 0))
                .build();

        DoctorRegistrationRequest request = validRequestBuilder("backtoback.doctor@example.com")
                .openingHours(new LinkedHashSet<>(Set.of(morning, afternoon)))
                .build();

        ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                "/api/doctors/register", request, DoctorAuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(doctorRepository.findByEmail("backtoback.doctor@example.com")).isPresent();
    }

    @Test
    void login_validCredentials_returns200WithToken() {
        DoctorRegistrationRequest registration = validRequestBuilder("login.doctor@example.com").build();
        restTemplate.postForEntity("/api/doctors/register", registration, DoctorAuthResponse.class);

        DoctorLoginRequest loginRequest = new DoctorLoginRequest("login.doctor@example.com", "secret123");
        ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                "/api/doctors/login", loginRequest, DoctorAuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getEmail()).isEqualTo("login.doctor@example.com");
        assertThat(response.getBody().getToken()).isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        DoctorRegistrationRequest registration = validRequestBuilder("wrongpass.doctor@example.com").build();
        restTemplate.postForEntity("/api/doctors/register", registration, DoctorAuthResponse.class);

        DoctorLoginRequest loginRequest = new DoctorLoginRequest("wrongpass.doctor@example.com", "not-the-password");
        ResponseEntity<String> response = restTemplate.postForEntity("/api/doctors/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("title").asText()).isEqualTo("Invalid Credentials");
    }

    @Test
    void login_unknownEmail_returns401() {
        DoctorLoginRequest loginRequest = new DoctorLoginRequest("nobody@example.com", "whatever123");
        ResponseEntity<String> response = restTemplate.postForEntity("/api/doctors/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getDoctorById_multipleSpecialtiesLanguagesOpeningHours_returnsNoCartesianDuplicates() {
        Specialty generalPractice = specialtyRepository.findByName("General Practice").orElseThrow();
        Specialty cardiology = specialtyRepository.findByName("Cardiology").orElseThrow();

        AddressDto address = AddressDto.builder()
                .street("Main St")
                .houseNumber("1")
                .postalCode("12345")
                .city("Berlin")
                .country("Germany")
                .build();

        OpeningHoursDto monday = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();
        OpeningHoursDto tuesday = OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        DoctorRegistrationRequest request = DoctorRegistrationRequest.builder()
                .firstName("Jane")
                .lastName("Doe")
                .email("jane.doe.multi@example.com")
                .password("secret123")
                .phoneNumber("+491234567")
                .address(address)
                .specialtyIds(Set.of(generalPractice.getId(), cardiology.getId()))
                .openingHours(Set.of(monday, tuesday))
                .languages(Set.of(Language.ENGLISH, Language.GERMAN))
                .build();

        ResponseEntity<DoctorAuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/doctors/register", request, DoctorAuthResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID doctorId = registerResponse.getBody().getId();

        ResponseEntity<DoctorResponse> detailResponse = restTemplate.getForEntity(
                "/api/doctors/{id}", DoctorResponse.class, doctorId);

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        DoctorResponse body = detailResponse.getBody();
        assertThat(body.getSpecialties()).hasSize(2);
        assertThat(body.getOpeningHours()).hasSize(2);
        assertThat(body.getLanguages()).hasSize(2);
    }

    // No round-trip test (token from register authenticating a doctor-scoped call): after
    // Part A's removal of the old authenticated create-doctor endpoint, doctor-service has no
    // remaining endpoint that requires a DOCTOR token. anyRequest().authenticated() is still
    // the fallback for a future doctor-scoped write, but nothing is mapped to it today.
}
