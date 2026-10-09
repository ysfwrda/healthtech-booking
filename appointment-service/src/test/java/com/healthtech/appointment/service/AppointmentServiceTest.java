package com.healthtech.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.SlotAlreadyBookedException;
import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.outbox.OutboxEventWriter;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import com.healthtech.appointment.service.booking.BookingRule;
import com.healthtech.appointment.service.booking.NotInPastRule;
import com.healthtech.appointment.service.booking.SlotAlignedRule;
import com.healthtech.appointment.service.booking.WithinOpeningHoursRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AppointmentService appointmentService;

    // "Now" for booking tests is fixed well before the hardcoded 2026-08-10 slots, so they
    // stay in the future whenever the suite runs.
    private static final Clock BOOKING_CLOCK =
            Clock.fixed(LocalDateTime.of(2026, 8, 1, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    // The production rules, in their @Order: alignment, opening hours, not in the past.
    private static List<BookingRule> bookingRules() {
        SlotPolicy slotPolicy = new SlotPolicy();
        return List.of(new SlotAlignedRule(slotPolicy), new WithinOpeningHoursRule(slotPolicy),
                new NotInPastRule(BOOKING_CLOCK));
    }

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                validPatientRepository,
                validDoctorRepository,
                bookingRules(),
                new OutboxEventWriter(outboxRepository, objectMapper),
                BOOKING_CLOCK
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
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .id(appointmentId)
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
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.bookAppointment(request, patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, times(1)).saveAndFlush(appointment);
        verify(outboxRepository, times(1)).save(argThat(row ->
                row.getTopic().equals("appointment.booked")
                        && row.getAggregateId().equals(appointmentId.toString())
                        && row.getPayload() != null));
    }

    @Test
    void bookAppointment_shouldSetPatientIdFromTokenParameterNotFromMappedRequest() {
        // Arrange: the mapper produces an entity carrying some other patientId (as it
        // would if the DTO still had a stray value); the token-derived id must win.
        UUID tokenPatientId = UUID.randomUUID();
        UUID mapperPatientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);

        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
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
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, tokenPatientId);

        // Assert: the persisted appointment carries the token's patientId, not the mapper's
        ArgumentCaptor<Appointment> savedCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).saveAndFlush(savedCaptor.capture());
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

        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository, times(1)).save(appointment);
        verify(outboxRepository, times(1)).save(argThat(row ->
                row.getTopic().equals("appointment.cancelled")
                        && row.getAggregateId().equals(appointment.getId().toString())
                        && row.getPayload() != null));
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

        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointment.getId(), callerPatientId))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void cancelAppointment_appointmentNotFound_shouldThrowAppointmentNotFoundException() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(Optional.empty());

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointmentId, UUID.randomUUID()))
                .isInstanceOf(AppointmentNotFoundException.class)
                .hasMessage("Appointment not found: " + appointmentId);

        verify(appointmentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void bookAppointment_slotAlreadyTakenAtSaveTime_shouldThrowSlotAlreadyBookedExceptionAndNotPublishEvent() {
        // Arrange: the availability check passed (no known conflict), but a concurrent
        // booking wins the race at the DB unique-constraint level. save() surfaces this
        // as a DataIntegrityViolationException, which the service must translate to the
        // typed SlotAlreadyBookedException (mapped to 409 by GlobalExceptionHandler),
        // without publishing a booked event for a booking that didn't happen.
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
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Act & Assert
        assertThatThrownBy(() -> appointmentService.bookAppointment(request, patientId))
                .isInstanceOf(SlotAlreadyBookedException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void bookAppointment_slotInThePast_shouldThrowSlotInPastAndNotSaveOrPublish() {
        // Arrange: aligned, within opening hours, but before the fixed "now" (2026-08-01 12:00)
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime pastSlot = LocalDateTime.of(2026, 7, 27, 10, 0);
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(pastSlot)
                .type(INITIAL_CONSULTATION)
                .build();
        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(pastSlot)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(patientId, doctorId, pastSlot);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);

        // Act & Assert
        assertThatThrownBy(() -> appointmentService.bookAppointment(request, patientId))
                .isInstanceOf(SlotInPastException.class);

        verify(appointmentRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void bookAppointment_shouldPublishEventWithCorrectAppointmentFields() throws Exception {
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
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, patientId);

        // Assert: event carries the saved appointment's IDs
        ArgumentCaptor<com.healthtech.appointment.outbox.OutboxMessage> rowCaptor =
                ArgumentCaptor.forClass(com.healthtech.appointment.outbox.OutboxMessage.class);
        verify(outboxRepository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getTopic()).isEqualTo("appointment.booked");
        AppointmentBooked event = objectMapper.readValue(rowCaptor.getValue().getPayload(), AppointmentBooked.class);
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
                .id(UUID.randomUUID())
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
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request, patientId);

        // Assert: duration is server-set regardless of request content
        assertThat(appointment.getDuration()).isEqualTo(30);
    }

    @Test
    void cancelAppointment_alreadyCancelledAppointment_shouldReturnCurrentStateWithoutSavingOrPublishing() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentMapper.toResponse(appointment)).thenReturn(
                AppointmentResponse.builder().status(AppointmentStatus.CANCELLED).build());

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId(), patientId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void cancelAppointment_alreadyCancelledOwnedBySomeoneElse_shouldThrowAccessDenied() {
        // Arrange: ownership is checked before the idempotent short-circuit
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointment.getId(), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        verify(appointmentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
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

    @Test
    void cancelAppointment_shouldStampCancelledAtFromTheInjectedClock() throws Exception {
        // Arrange
        LocalDateTime now = LocalDateTime.of(2030, 3, 18, 14, 5);
        AppointmentService serviceAtFixedTime = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                validPatientRepository,
                validDoctorRepository,
                bookingRules(),
                new OutboxEventWriter(outboxRepository, objectMapper),
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        );
        UUID patientId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CONFIRMED)
                .build();
        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        serviceAtFixedTime.cancelAppointment(appointment.getId(), patientId);

        // Assert
        ArgumentCaptor<com.healthtech.appointment.outbox.OutboxMessage> rowCaptor =
                ArgumentCaptor.forClass(com.healthtech.appointment.outbox.OutboxMessage.class);
        verify(outboxRepository).save(rowCaptor.capture());
        AppointmentCancelled event = objectMapper.readValue(rowCaptor.getValue().getPayload(), AppointmentCancelled.class);
        assertThat(event.getCancelledAt()).isEqualTo(now);
    }
}
