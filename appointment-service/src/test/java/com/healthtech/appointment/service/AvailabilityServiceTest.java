package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

// getAvailableSlots tests, moved verbatim from AppointmentServiceTest when availability
// was split out of AppointmentService.
@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private ValidDoctorRepository validDoctorRepository;

    private AvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        availabilityService = new AvailabilityService(
                validDoctorRepository,
                appointmentRepository,
                new SlotPolicy()
        );
    }

    // --- getAvailableSlots ---
    // A fixed future date is used so opening-hours blocks can be matched to it by
    // date.getDayOfWeek() without hand-calculating a weekday. It is never "today" at
    // test run time, so the past-slot filter (step 4) never engages here.
    private static final LocalDate FUTURE_DATE = LocalDate.of(2030, 3, 18);

    private void stubDoctorOpeningHours(UUID doctorId, Set<OpeningHours> openingHours) {
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.of(
                ValidDoctor.builder()
                        .doctorId(doctorId)
                        .firstName("John")
                        .lastName("Smith")
                        .openingHours(openingHours)
                        .build()));
    }

    private void stubTakenAppointments(UUID doctorId, LocalDate date, List<Appointment> taken) {
        when(appointmentRepository.findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
                doctorId, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), AppointmentStatus.CANCELLED))
                .thenReturn(taken);
    }

    @Test
    void getAvailableSlots_gridBoundaries_shouldReturnExactThirtyMinuteSlotsWithinOpeningHours() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        stubDoctorOpeningHours(doctorId, Set.of(OpeningHours.builder()
                .dayOfWeek(FUTURE_DATE.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build()));
        stubTakenAppointments(doctorId, FUTURE_DATE, List.of());

        List<LocalDateTime> expected = List.of(
                FUTURE_DATE.atTime(9, 0), FUTURE_DATE.atTime(9, 30),
                FUTURE_DATE.atTime(10, 0), FUTURE_DATE.atTime(10, 30),
                FUTURE_DATE.atTime(11, 0), FUTURE_DATE.atTime(11, 30),
                FUTURE_DATE.atTime(12, 0), FUTURE_DATE.atTime(12, 30),
                FUTURE_DATE.atTime(13, 0), FUTURE_DATE.atTime(13, 30),
                FUTURE_DATE.atTime(14, 0), FUTURE_DATE.atTime(14, 30),
                FUTURE_DATE.atTime(15, 0), FUTURE_DATE.atTime(15, 30),
                FUTURE_DATE.atTime(16, 0), FUTURE_DATE.atTime(16, 30)
        );

        // Act
        AvailableSlotsResponse result = availabilityService.getAvailableSlots(doctorId, FUTURE_DATE);

        // Assert
        assertThat(result.getAvailableSlots()).hasSize(16);
        assertThat(result.getAvailableSlots()).containsExactlyElementsOf(expected);
        assertThat(result.getAvailableSlots().getFirst()).isEqualTo(FUTURE_DATE.atTime(9, 0));
        assertThat(result.getAvailableSlots().get(15)).isEqualTo(FUTURE_DATE.atTime(16, 30));
        assertThat(result.getAvailableSlots()).doesNotContain(FUTURE_DATE.atTime(17, 0));
    }

    @Test
    void getAvailableSlots_splitShift_shouldSkipLunchGapAndReturnBothBlocks() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        stubDoctorOpeningHours(doctorId, Set.of(
                OpeningHours.builder().dayOfWeek(FUTURE_DATE.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).build(),
                OpeningHours.builder().dayOfWeek(FUTURE_DATE.getDayOfWeek())
                        .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(17, 0)).build()
        ));
        stubTakenAppointments(doctorId, FUTURE_DATE, List.of());

        List<LocalDateTime> expected = List.of(
                FUTURE_DATE.atTime(9, 0), FUTURE_DATE.atTime(9, 30),
                FUTURE_DATE.atTime(10, 0), FUTURE_DATE.atTime(10, 30),
                FUTURE_DATE.atTime(11, 0), FUTURE_DATE.atTime(11, 30),
                FUTURE_DATE.atTime(14, 0), FUTURE_DATE.atTime(14, 30),
                FUTURE_DATE.atTime(15, 0), FUTURE_DATE.atTime(15, 30),
                FUTURE_DATE.atTime(16, 0), FUTURE_DATE.atTime(16, 30)
        );

        // Act
        AvailableSlotsResponse result = availabilityService.getAvailableSlots(doctorId, FUTURE_DATE);

        // Assert
        // Opening-hours blocks come from a Set (ValidDoctor.getOpeningHours()), so the
        // two blocks are not guaranteed to be processed in chronological order; only
        // the full unordered content is asserted, plus the boundary values below (which
        // do not depend on block iteration order).
        assertThat(result.getAvailableSlots()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.getAvailableSlots()).doesNotContain(
                FUTURE_DATE.atTime(12, 0), FUTURE_DATE.atTime(12, 30),
                FUTURE_DATE.atTime(13, 0), FUTURE_DATE.atTime(13, 30));

        List<LocalDateTime> morningSlots = result.getAvailableSlots().stream()
                .filter(slot -> slot.toLocalTime().isBefore(LocalTime.NOON))
                .toList();
        List<LocalDateTime> afternoonSlots = result.getAvailableSlots().stream()
                .filter(slot -> !slot.toLocalTime().isBefore(LocalTime.NOON))
                .toList();
        assertThat(Collections.max(morningSlots)).isEqualTo(FUTURE_DATE.atTime(11, 30));
        assertThat(Collections.min(afternoonSlots)).isEqualTo(FUTURE_DATE.atTime(14, 0));
    }

    @Test
    void getAvailableSlots_noOpeningHoursForRequestedDay_shouldReturnEmptyListWithoutThrowing() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        DayOfWeek otherDay = FUTURE_DATE.getDayOfWeek().plus(1);
        stubDoctorOpeningHours(doctorId, Set.of(OpeningHours.builder()
                .dayOfWeek(otherDay)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build()));
        stubTakenAppointments(doctorId, FUTURE_DATE, List.of());

        // Act
        AvailableSlotsResponse result = availabilityService.getAvailableSlots(doctorId, FUTURE_DATE);

        // Assert
        assertThat(result.getAvailableSlots()).isEmpty();
    }

    @Test
    void getAvailableSlots_takenAppointment_shouldExcludeOnlyThatSlot() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        stubDoctorOpeningHours(doctorId, Set.of(OpeningHours.builder()
                .dayOfWeek(FUTURE_DATE.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build()));
        Appointment taken = Appointment.builder()
                .doctorId(doctorId)
                .dateTime(FUTURE_DATE.atTime(10, 0))
                .status(AppointmentStatus.CONFIRMED)
                .build();
        stubTakenAppointments(doctorId, FUTURE_DATE, List.of(taken));

        // Act
        AvailableSlotsResponse result = availabilityService.getAvailableSlots(doctorId, FUTURE_DATE);

        // Assert
        assertThat(result.getAvailableSlots()).hasSize(15);
        assertThat(result.getAvailableSlots()).doesNotContain(FUTURE_DATE.atTime(10, 0));
        assertThat(result.getAvailableSlots()).contains(FUTURE_DATE.atTime(9, 30), FUTURE_DATE.atTime(10, 30));
    }

    @Test
    void getAvailableSlots_unknownDoctor_shouldThrowDoctorNotFoundException() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.empty());

        // Act and Assert
        assertThatThrownBy(() -> availabilityService.getAvailableSlots(doctorId, FUTURE_DATE))
                .isInstanceOf(DoctorNotFoundException.class);

        verify(appointmentRepository, never())
                .findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(any(), any(), any(), any());
    }

    @Test
    void getAvailableSlots_futureDate_shouldNotApplyPastSlotFilterAndReturnFullMinusTaken() {
        // A date one year out guarantees the "today only" past-slot filter (step 4 in
        // the service) is a no-op, so the result depends only on hours and taken
        // appointments, not on the real current time. This is not a wall-clock
        // assertion: LocalDate.now() only picks which date is "safely not today", the
        // expected values below do not depend on when the test actually runs.
        // Arrange
        UUID doctorId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusYears(1);
        stubDoctorOpeningHours(doctorId, Set.of(OpeningHours.builder()
                .dayOfWeek(futureDate.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build()));
        Appointment taken = Appointment.builder()
                .doctorId(doctorId)
                .dateTime(futureDate.atTime(13, 0))
                .status(AppointmentStatus.CONFIRMED)
                .build();
        stubTakenAppointments(doctorId, futureDate, List.of(taken));

        List<LocalDateTime> expected = List.of(
                futureDate.atTime(9, 0), futureDate.atTime(9, 30),
                futureDate.atTime(10, 0), futureDate.atTime(10, 30),
                futureDate.atTime(11, 0), futureDate.atTime(11, 30),
                futureDate.atTime(12, 0), futureDate.atTime(12, 30),
                futureDate.atTime(13, 30),
                futureDate.atTime(14, 0), futureDate.atTime(14, 30),
                futureDate.atTime(15, 0), futureDate.atTime(15, 30),
                futureDate.atTime(16, 0), futureDate.atTime(16, 30)
        );

        // Act
        AvailableSlotsResponse result = availabilityService.getAvailableSlots(doctorId, futureDate);

        // Assert
        assertThat(result.getAvailableSlots()).containsExactlyElementsOf(expected);
        assertThat(result.getAvailableSlots()).doesNotContain(futureDate.atTime(13, 0));
    }

    // Note: the past-slot filter (today only, step 4 in getAvailableSlots) is not
    // unit-tested here because it depends on LocalDateTime.now(). Making it testable
    // would require injecting a java.time.Clock into AvailabilityService. Flagged as a
    // possible future refactor.
}
