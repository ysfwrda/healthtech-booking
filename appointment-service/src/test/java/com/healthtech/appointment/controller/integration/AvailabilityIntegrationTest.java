package com.healthtech.appointment.controller.integration;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AvailabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    ValidDoctorRepository validDoctorRepository;
    @Autowired
    AppointmentRepository appointmentRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void getAvailability_excludesBookedSlot_includesAdjacentSlots() {
        LocalDate target = LocalDate.now().plusWeeks(1);
        Set<OpeningHours> openingHours = new HashSet<>();
        openingHours.add(OpeningHours.builder()
                .dayOfWeek(target.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0)).build());

        ValidDoctor seededDoctor = ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(openingHours)
                .build();
        seededDoctor = validDoctorRepository.save(seededDoctor);

        LocalDateTime bookedSlot = LocalDateTime.of(target, LocalTime.of(10, 0));
        Appointment existingAppointment = Appointment.builder()
                .patientId(UUID.randomUUID())
                .doctorId(seededDoctor.getDoctorId())
                .dateTime(bookedSlot)
                .duration(30)
                .type(AppointmentType.VACCINATION)
                .status(AppointmentStatus.CONFIRMED)
                .notes("Pre-existing booking")
                .build();
        appointmentRepository.save(existingAppointment);

        ResponseEntity<AvailableSlotsResponse> response = restTemplate.getForEntity(
                "/api/availability?doctorId=" + seededDoctor.getDoctorId() + "&date=" + target,
                AvailableSlotsResponse.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<LocalDateTime> slots = response.getBody().getAvailableSlots();
        assertThat(slots).doesNotContain(bookedSlot);
        assertThat(slots).contains(bookedSlot.minusMinutes(30), bookedSlot.plusMinutes(30));
    }
}
