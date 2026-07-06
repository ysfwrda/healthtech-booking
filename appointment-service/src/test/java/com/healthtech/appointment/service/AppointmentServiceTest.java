package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.UnknownDoctorException;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.healthtech.appointment.domain.AppointmentType.INITIAL_CONSULTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentMapper appointmentMapper;

    @Mock
    private ValidPatientRepository validPatientRepository;

    @Mock
    private ValidDoctorRepository validDoctorRepository;

    @Mock
    private KafkaTemplate<String, AppointmentBooked> bookedEventKafkaTemplate;

    @Mock
    private KafkaTemplate<String, AppointmentCancelled> cancelledEventKafkaTemplate;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                validPatientRepository,
                validDoctorRepository,
                bookedEventKafkaTemplate,
                cancelledEventKafkaTemplate
        );
    }

    private void stubValidReadModel(UUID patientId, UUID doctorId, LocalDateTime dateTime) {
        when(validPatientRepository.findById(patientId)).thenReturn(Optional.of(
                ValidPatient.builder().patientId(patientId).firstName("Jane").lastName("Doe").email("jane@example.com").build()));
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.of(
                ValidDoctor.builder()
                        .doctorId(doctorId)
                        .firstName("John")
                        .lastName("Smith")
                        .openingHours(Set.of(OpeningHours.builder()
                                .dayOfWeek(dateTime.getDayOfWeek())
                                .startTime(LocalTime.of(8, 0))
                                .endTime(LocalTime.of(18, 0))
                                .build()))
                        .build()));
    }

    @Test
    void bookAppointment_shouldSaveWithConfirmedStatusAndPublishEvent() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .status(AppointmentStatus.CONFIRMED)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.bookAppointment(request, patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, times(1)).save(appointment);
        verify(bookedEventKafkaTemplate, times(1)).send(eq("appointment.booked"), any(AppointmentBooked.class));    }

    @Test
    void bookAppointment_shouldSetPatientIdFromTokenParameterNotFromMappedRequest() {
        // Arrange: the mapper produces an entity carrying some other patientId (as it
        // would if the DTO still had a stray value); the token-derived id must win.
        UUID tokenPatientId = UUID.randomUUID();
        UUID mapperPatientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);

        Appointment appointment = Appointment.builder()
                .patientId(mapperPatientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(tokenPatientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, tokenPatientId);

        // Assert: the persisted appointment carries the token's patientId, not the mapper's
        ArgumentCaptor<Appointment> savedCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getPatientId()).isEqualTo(tokenPatientId);
        assertThat(savedCaptor.getValue().getPatientId()).isNotEqualTo(mapperPatientId);
    }

    @Test
    void cancelAppointment_shouldUpdateStatusToCancelledAndPublishEvent() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .type(INITIAL_CONSULTATION)
                .id(UUID.randomUUID())
                .patientId(patientId)
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository, times(1)).save(appointment);
        verify(cancelledEventKafkaTemplate, times(1)).send(eq("appointment.cancelled"), any(AppointmentCancelled.class));
    }

    @Test
    void cancelAppointment_mismatchedPatientId_shouldThrowAccessDeniedAndNotCancelOrSave() {
        // Arrange
        UUID ownerPatientId = UUID.randomUUID();
        UUID callerPatientId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(ownerPatientId)
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointment.getId(), callerPatientId))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, never()).save(any());
        verify(cancelledEventKafkaTemplate, never()).send(any(), any());
    }

    @Test
    void cancelAppointment_appointmentNotFound_shouldThrowAppointmentNotFoundException() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointmentId, UUID.randomUUID()))
                .isInstanceOf(AppointmentNotFoundException.class)
                .hasMessage("Appointment not found: " + appointmentId);

        verify(appointmentRepository, never()).save(any());
        verify(cancelledEventKafkaTemplate, never()).send(any(), any());
    }

    @Test
    void bookAppointment_shouldPublishEventWithCorrectAppointmentFields() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .duration(30)
                .type(INITIAL_CONSULTATION)
                .createdAt(LocalDateTime.now())
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, patientId);

        // Assert: event carries the saved appointment's IDs
        ArgumentCaptor<AppointmentBooked> eventCaptor = ArgumentCaptor.forClass(AppointmentBooked.class);
        verify(bookedEventKafkaTemplate).send(eq("appointment.booked"), eventCaptor.capture());
        AppointmentBooked event = eventCaptor.getValue();
        assertThat(event.getAppointmentId()).isEqualTo(appointment.getId());
        assertThat(event.getPatientId()).isEqualTo(patientId);
        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getDuration()).isEqualTo(30);
        assertThat(event.getPatientName()).isEqualTo("Jane Doe");
        assertThat(event.getPatientEmail()).isEqualTo("jane@example.com");
        assertThat(event.getDoctorName()).isEqualTo("John Smith");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getBookedAt()).isNotNull();
    }

    @Test
    void bookAppointment_shouldSetDurationToThirtyServerSide() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, patientId);

        // Assert: duration is server-set regardless of request content
        assertThat(appointment.getDuration()).isEqualTo(30);
    }

    @Test
    void cancelAppointment_alreadyCancelledAppointment_shouldOverwriteStatusAndPublishEvent() {
        // Documents current behavior: no guard against double-cancellation.
        // Arrange
        UUID patientId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(
                AppointmentResponse.builder().status(AppointmentStatus.CANCELLED).build());

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(cancelledEventKafkaTemplate, times(1)).send(eq("appointment.cancelled"), any(AppointmentCancelled.class));
    }

    @Test
    void getAppointmentsForPatient_shouldReturnMappedResponsesOrderedByDateTimeAsc() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        Appointment confirmed = Appointment.builder()
                .id(UUID.randomUUID()).patientId(patientId).type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CONFIRMED).dateTime(LocalDateTime.of(2026, 8, 10, 9, 0)).build();
        Appointment cancelled = Appointment.builder()
                .id(UUID.randomUUID()).patientId(patientId).type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CANCELLED).dateTime(LocalDateTime.of(2026, 8, 11, 9, 0)).build();

        when(appointmentRepository.findByPatientIdOrderByDateTimeAsc(patientId))
                .thenReturn(List.of(confirmed, cancelled));
        when(appointmentMapper.toResponse(confirmed))
                .thenReturn(AppointmentResponse.builder().id(confirmed.getId()).status(AppointmentStatus.CONFIRMED).build());
        when(appointmentMapper.toResponse(cancelled))
                .thenReturn(AppointmentResponse.builder().id(cancelled.getId()).status(AppointmentStatus.CANCELLED).build());

        // Act
        List<AppointmentResponse> result = appointmentService.getAppointmentsForPatient(patientId);

        // Assert: cancelled appointments are included, not filtered out
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(result.get(1).getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
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
        AvailableSlotsResponse result = appointmentService.getAvailableSlots(doctorId, FUTURE_DATE);

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
        AvailableSlotsResponse result = appointmentService.getAvailableSlots(doctorId, FUTURE_DATE);

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
        AvailableSlotsResponse result = appointmentService.getAvailableSlots(doctorId, FUTURE_DATE);

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
        AvailableSlotsResponse result = appointmentService.getAvailableSlots(doctorId, FUTURE_DATE);

        // Assert
        assertThat(result.getAvailableSlots()).hasSize(15);
        assertThat(result.getAvailableSlots()).doesNotContain(FUTURE_DATE.atTime(10, 0));
        assertThat(result.getAvailableSlots()).contains(FUTURE_DATE.atTime(9, 30), FUTURE_DATE.atTime(10, 30));
    }

    @Test
    void getAvailableSlots_unknownDoctor_shouldThrowUnknownDoctorException() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.empty());

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.getAvailableSlots(doctorId, FUTURE_DATE))
                .isInstanceOf(UnknownDoctorException.class);

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
        AvailableSlotsResponse result = appointmentService.getAvailableSlots(doctorId, futureDate);

        // Assert
        assertThat(result.getAvailableSlots()).containsExactlyElementsOf(expected);
        assertThat(result.getAvailableSlots()).doesNotContain(futureDate.atTime(13, 0));
    }

    // Note: the past-slot filter (today only, step 4 in getAvailableSlots) is not
    // unit-tested here because it depends on LocalDateTime.now(). Making it testable
    // would require injecting a java.time.Clock into AppointmentService. Flagged as a
    // possible future refactor.
}
