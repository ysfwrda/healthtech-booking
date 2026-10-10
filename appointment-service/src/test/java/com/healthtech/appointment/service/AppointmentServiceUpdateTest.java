package com.healthtech.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.config.ChangePolicyProperties;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.event.AppointmentChanged;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotChangeableException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.ChangeWindowClosedException;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.exception.SlotAlreadyBookedException;
import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.outbox.OutboxEventWriter;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
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
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.healthtech.appointment.domain.AppointmentType.FOLLOW_UP;
import static com.healthtech.appointment.domain.AppointmentType.INITIAL_CONSULTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit tests for AppointmentService.updateAppointment with a fixed clock and the production booking rules.
@ExtendWith(MockitoExtension.class)
class AppointmentServiceUpdateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final LocalDateTime CURRENT_SLOT = LocalDateTime.of(2026, 8, 10, 10, 0);

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

    private AppointmentService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SlotPolicy slotPolicy = new SlotPolicy();
        service = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                validPatientRepository,
                validDoctorRepository,
                List.of(new SlotAlignedRule(slotPolicy), new WithinOpeningHoursRule(slotPolicy), new NotInPastRule(CLOCK)),
                new OutboxEventWriter(outboxRepository, objectMapper),
                CLOCK,
                new ChangePolicyProperties(48));
    }

    private Appointment appointmentAt(LocalDateTime dateTime) {
        return Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .duration(30)
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CONFIRMED)
                .notes("old notes")
                .build();
    }

    private void stubLoaded(Appointment appointment) {
        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    }

    private void stubSaveAndResponse(Appointment appointment) {
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());
    }

    // The doctor is open 08:00-18:00 every day.
    private void stubDoctor() {
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.of(ValidDoctor.builder()
                .doctorId(doctorId)
                .firstName("John")
                .lastName("Smith")
                .openingHours(Arrays.stream(DayOfWeek.values())
                        .map(day -> OpeningHours.builder().dayOfWeek(day)
                                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(18, 0)).build())
                        .collect(Collectors.toSet()))
                .build()));
    }

    private AppointmentChanged publishedEvent() throws Exception {
        ArgumentCaptor<OutboxMessage> rowCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getTopic()).isEqualTo("appointment.changed");
        return objectMapper.readValue(rowCaptor.getValue().getPayload(), AppointmentChanged.class);
    }

    private static AppointmentUpdateRequest time(LocalDateTime dateTime) {
        return AppointmentUpdateRequest.builder().dateTime(dateTime).build();
    }

    private static AppointmentUpdateRequest type(AppointmentType type) {
        return AppointmentUpdateRequest.builder().type(type).build();
    }

    private static AppointmentUpdateRequest notes(String notes) {
        return AppointmentUpdateRequest.builder().notes(notes).build();
    }

    // --- what is applied and published ---

    @Test
    void updateAppointment_typeOnly_changesTypeAndPublishesEventWithPreviousType() throws Exception {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId);

        // Assert
        assertThat(appointment.getType()).isEqualTo(FOLLOW_UP);
        assertThat(appointment.getDateTime()).isEqualTo(CURRENT_SLOT);
        AppointmentChanged event = publishedEvent();
        assertThat(event.getType()).isEqualTo(FOLLOW_UP);
        assertThat(event.getPreviousType()).isEqualTo(INITIAL_CONSULTATION);
        assertThat(event.getDateTime()).isEqualTo(CURRENT_SLOT);
        assertThat(event.getPreviousDateTime()).isEqualTo(CURRENT_SLOT);
        assertThat(event.getAppointmentId()).isEqualTo(appointment.getId());
        assertThat(event.getPatientId()).isEqualTo(patientId);
        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getChangedAt()).isEqualTo(NOW);
        verifyNoInteractions(validDoctorRepository);
    }

    @Test
    void updateAppointment_timeOnly_movesAppointmentAndPublishesEventWithPreviousTime() throws Exception {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        LocalDateTime newSlot = LocalDateTime.of(2026, 8, 11, 14, 30);
        stubLoaded(appointment);
        stubDoctor();
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), time(newSlot), patientId);

        // Assert
        assertThat(appointment.getDateTime()).isEqualTo(newSlot);
        assertThat(appointment.getType()).isEqualTo(INITIAL_CONSULTATION);
        AppointmentChanged event = publishedEvent();
        assertThat(event.getDateTime()).isEqualTo(newSlot);
        assertThat(event.getPreviousDateTime()).isEqualTo(CURRENT_SLOT);
    }

    @Test
    void updateAppointment_typeAndTime_appliesBoth() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        LocalDateTime newSlot = LocalDateTime.of(2026, 8, 12, 9, 0);
        stubLoaded(appointment);
        stubDoctor();
        stubSaveAndResponse(appointment);
        AppointmentUpdateRequest request = AppointmentUpdateRequest.builder().type(FOLLOW_UP).dateTime(newSlot).build();

        // Act
        service.updateAppointment(appointment.getId(), request, patientId);

        // Assert
        assertThat(appointment.getType()).isEqualTo(FOLLOW_UP);
        assertThat(appointment.getDateTime()).isEqualTo(newSlot);
        verify(outboxRepository).save(any(OutboxMessage.class));
    }

    @Test
    void updateAppointment_notesOnly_savesNotesAndPublishesNoEvent() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), notes("new notes"), patientId);

        // Assert
        assertThat(appointment.getNotes()).isEqualTo("new notes");
        verify(appointmentRepository).saveAndFlush(appointment);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void updateAppointment_emptyNotes_clearsNotes() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), notes(""), patientId);

        // Assert
        assertThat(appointment.getNotes()).isNull();
    }

    @Test
    void updateAppointment_sameTimeAndType_publishesNoEventAndSkipsDoctorLookup() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);
        AppointmentUpdateRequest request = AppointmentUpdateRequest.builder()
                .type(INITIAL_CONSULTATION).dateTime(CURRENT_SLOT).build();

        // Act
        service.updateAppointment(appointment.getId(), request, patientId);

        // Assert
        verify(outboxRepository, never()).save(any());
        verifyNoInteractions(validDoctorRepository);
    }

    // --- the notice period ---

    @Test
    void updateAppointment_startsExactlyAtMinimumNotice_isAllowed() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(48));
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId);

        // Assert
        assertThat(appointment.getType()).isEqualTo(FOLLOW_UP);
    }

    @Test
    void updateAppointment_startsOneMinuteInsideMinimumNotice_throwsChangeWindowClosed() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.plusHours(48).minusMinutes(1));
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId))
                .isInstanceOf(ChangeWindowClosedException.class)
                .hasMessageContaining("48 hours");
        assertThat(appointment.getType()).isEqualTo(INITIAL_CONSULTATION);
        verify(appointmentRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void updateAppointment_alreadyStarted_throwsChangeWindowClosed() {
        // Arrange
        Appointment appointment = appointmentAt(NOW.minusHours(1));
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId))
                .isInstanceOf(ChangeWindowClosedException.class);
    }

    // --- the new time ---

    @Test
    void updateAppointment_newTimeOneSlotBeforeNow_throwsSlotInPast() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(NOW.minusMinutes(30)), patientId))
                .isInstanceOf(SlotInPastException.class);
        assertThat(appointment.getDateTime()).isEqualTo(CURRENT_SLOT);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAppointment_newTimeExactlyNow_isAccepted() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), time(NOW), patientId);

        // Assert
        assertThat(appointment.getDateTime()).isEqualTo(NOW);
    }

    @Test
    void updateAppointment_newTimeNotOnTheSlotGrid_throwsSlotNotAligned() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 10, 15)), patientId))
                .isInstanceOf(SlotNotAlignedException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAppointment_newTimeStartsAtOpeningTime_isAccepted() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 8, 0)), patientId);

        // Assert
        assertThat(appointment.getDateTime()).isEqualTo(LocalDateTime.of(2026, 8, 11, 8, 0));
    }

    @Test
    void updateAppointment_newTimeEndsAtClosingTime_isAccepted() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();
        stubSaveAndResponse(appointment);

        // Act
        service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 17, 30)), patientId);

        // Assert
        assertThat(appointment.getDateTime()).isEqualTo(LocalDateTime.of(2026, 8, 11, 17, 30));
    }

    @Test
    void updateAppointment_newTimeOneSlotBeforeOpening_throwsOutsideOpeningHours() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 7, 30)), patientId))
                .isInstanceOf(OutsideOpeningHoursException.class);
    }

    @Test
    void updateAppointment_newTimeStartsAtClosingTime_throwsOutsideOpeningHours() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 18, 0)), patientId))
                .isInstanceOf(OutsideOpeningHoursException.class);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void updateAppointment_newTimeAlreadyTaken_throwsSlotAlreadyBookedAndPublishesNothing() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        stubDoctor();
        when(appointmentRepository.saveAndFlush(appointment)).thenThrow(new DataIntegrityViolationException("ux_active_appointment"));

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 10, 0)), patientId))
                .isInstanceOf(SlotAlreadyBookedException.class);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void updateAppointment_doctorMissingFromReadModel_throwsDoctorNotFound() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), time(LocalDateTime.of(2026, 8, 11, 10, 0)), patientId))
                .isInstanceOf(DoctorNotFoundException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    // --- who and what may be changed ---

    @Test
    void updateAppointment_cancelledAppointment_throwsNotChangeable() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        appointment.setStatus(AppointmentStatus.CANCELLED);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId))
                .isInstanceOf(AppointmentNotChangeableException.class);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAppointment_otherPatientsAppointment_throwsAccessDeniedAndChangesNothing() {
        // Arrange
        Appointment appointment = appointmentAt(CURRENT_SLOT);
        stubLoaded(appointment);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(appointment.getId(), type(FOLLOW_UP), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);
        assertThat(appointment.getType()).isEqualTo(INITIAL_CONSULTATION);
        verify(appointmentRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void updateAppointment_unknownAppointment_throwsNotFound() {
        // Arrange
        UUID unknownId = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointment(unknownId, type(FOLLOW_UP), patientId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void updateAppointment_configuredNoticeOfSixHours_allowsAppointmentSevenHoursAway() {
        // Arrange
        AppointmentService shortNotice = new AppointmentService(appointmentRepository, appointmentMapper,
                validPatientRepository, validDoctorRepository, List.of(),
                new OutboxEventWriter(outboxRepository, objectMapper), CLOCK, new ChangePolicyProperties(6));
        Appointment appointment = appointmentAt(NOW.plusHours(7));
        stubLoaded(appointment);
        stubSaveAndResponse(appointment);

        // Act
        shortNotice.updateAppointment(appointment.getId(), type(FOLLOW_UP), patientId);

        // Assert
        assertThat(appointment.getType()).isEqualTo(FOLLOW_UP);
    }
}
