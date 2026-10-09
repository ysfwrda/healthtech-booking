package com.healthtech.appointment.controller.integration;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.readmodel.ValidDoctor;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AvailabilityIntegrationTest extends AbstractAppointmentApiTest {

    @Test
    void getAvailability_excludesBookedSlot_includesAdjacentSlots() {
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(target);

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
