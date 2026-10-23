package com.healthtech.appointment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.config.ChangePolicyProperties;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.ConflictReason;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotChangeableException;
import com.healthtech.appointment.exception.AppointmentNotConfirmableException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.exception.InvalidDateRangeException;
import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.exception.SlotAlreadyBookedException;
import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.exception.SlotOnVacationException;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.outbox.OutboxEventWriter;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.VacationPeriod;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import com.healthtech.appointment.service.booking.NotInPastRule;
import com.healthtech.appointment.service.booking.NotOnVacationRule;
import com.healthtech.appointment.service.booking.SlotAlignedRule;
import com.healthtech.appointment.service.booking.WithinOpeningHoursRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

// The doctor side of AppointmentService, with a fixed clock, the production booking rules and a real outbox writer
// over a mocked repository, so the published events can be read back.
@ExtendWith(MockitoExtension.class)
class DoctorAppointmentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final LocalDateTime CURRENT_SLOT = LocalDateTime.of(2026, 8, 10, 10, 0);

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private ValidPatientRepository validPatientRepository;
    @Mock private ValidDoctorRepository validDoctorRepository;
    @Mock private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        SlotPolicy slotPolicy = new SlotPolicy();
        service = new AppointmentService(appointmentRepository, appointmentMapper, validPatientRepository,
                validDoctorRepository,
                List.of(new SlotAlignedRule(slotPolicy), new WithinOpeningHoursRule(slotPolicy),
                        new NotInPastRule(CLOCK), new NotOnVacationRule()),
                new OutboxEventWriter(outboxRepository, objectMapper), CLOCK, new ChangePolicyProperties(48));
    }

    private Appointment appointment(AppointmentStatus status, LocalDateTime dateTime) {
        return Appointment.builder().id(UUID.randomUUID()).patientId(patientId).doctorId(doctorId)
                .dateTime(dateTime).duration(30).type(INITIAL_CONSULTATION).status(status).notes("patient notes")
                .build();
    }

    private void stubLoaded(Appointment appointment) {
        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    }

    private void stubSave(Appointment appointment) {
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());
    }

    private void stubSaveAndFlush(Appointment appointment) {
        when(appointmentRepository.saveAndFlush(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());
    }

    // Open 08:00-18:00 every day; vacation on 2026-08-20 only when asked.
    private void stubDoctor(boolean withVacation) {
        when(validDoctorRepository.findByIdForShare(doctorId)).thenReturn(Optional.of(ValidDoctor.builder()
                .doctorId(doctorId).firstName("John").lastName("Smith")
                .openingHours(Arrays.stream(DayOfWeek.values())
                        .map(day -> OpeningHours.builder().dayOfWeek(day)
                                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(18, 0)).build())
                        .collect(Collectors.toSet()))
                .vacations(withVacation ? Set.of(VacationPeriod.builder()
                        .startDate(LocalDate.of(2026, 8, 20)).endDate(LocalDate.of(2026, 8, 20)).build()) : Set.of())
                .build()));
    }

    private JsonNode publishedEvent(String topic) throws Exception {
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo(topic);
        return objectMapper.readTree(captor.getValue().getPayload());
    }

    private void assertNothingPublished() {
        verify(outboxRepository, never()).save(any());
    }

    // -- confirm -----------------------------------------------------------------

    @Test
    void confirm_pendingAppointment_becomesConfirmedAndPublishesConfirmedEvent() throws Exception {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        stubLoaded(a);
        stubSave(a);

        // Act
        service.confirmAppointment(a.getId(), doctorId);

        // Assert
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        JsonNode event = publishedEvent("appointment.confirmed");
        assertThat(event.get("appointmentId").asText()).isEqualTo(a.getId().toString());
        assertThat(event.get("patientId").asText()).isEqualTo(patientId.toString());
        assertThat(event.get("doctorId").asText()).isEqualTo(doctorId.toString());
        assertThat(event.get("eventId").asText()).isNotBlank();
    }

    @Test
    void confirm_alreadyConfirmed_isANoOpWithoutSaveOrEvent() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        when(appointmentMapper.toResponse(a)).thenReturn(AppointmentResponse.builder().build());

        // Act
        service.confirmAppointment(a.getId(), doctorId);

        // Assert
        verify(appointmentRepository, never()).save(any());
        assertNothingPublished();
    }

    @Test
    void confirm_cancelledAppointment_throwsNotConfirmable() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CANCELLED, CURRENT_SLOT);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.confirmAppointment(a.getId(), doctorId))
                .isInstanceOf(AppointmentNotConfirmableException.class)
                .hasMessageContaining("cancelled");
        assertNothingPublished();
    }

    @Test
    void confirm_pendingAppointmentThatNoLongerFitsTheAvailability_throwsNotConfirmable() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        a.setConflictReason(ConflictReason.DOCTOR_VACATION);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.confirmAppointment(a.getId(), doctorId))
                .isInstanceOf(AppointmentNotConfirmableException.class)
                .hasMessageContaining("availability");
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertNothingPublished();
    }

    @Test
    void confirm_confirmedAppointmentThatIsFlagged_staysANoOp() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        a.setConflictReason(ConflictReason.OUTSIDE_OPENING_HOURS);
        stubLoaded(a);
        when(appointmentMapper.toResponse(a)).thenReturn(AppointmentResponse.builder().build());

        // Act
        service.confirmAppointment(a.getId(), doctorId);

        // Assert
        assertNothingPublished();
    }

    @Test
    void confirm_unknownAppointment_throwsNotFound() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.confirmAppointment(id, doctorId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void confirm_anotherDoctorsAppointment_throwsAccessDeniedAndChangesNothing() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.confirmAppointment(a.getId(), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertNothingPublished();
    }

    // -- cancel ------------------------------------------------------------------

    @Test
    void cancel_confirmedAppointment_becomesCancelledAndPublishesCancelledByDoctor() throws Exception {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubSave(a);

        // Act
        service.cancelAppointmentAsDoctor(a.getId(), doctorId);

        // Assert
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        JsonNode event = publishedEvent("appointment.cancelled");
        assertThat(event.get("cancelledBy").asText()).isEqualTo("DOCTOR");
        assertThat(event.get("appointmentId").asText()).isEqualTo(a.getId().toString());
    }

    @Test
    void cancel_pendingAppointment_isDeclinedAsACancel() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        stubLoaded(a);
        stubSave(a);

        // Act
        service.cancelAppointmentAsDoctor(a.getId(), doctorId);

        // Assert
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancel_oneMinuteBeforeTheStart_isAllowedWithoutNoticePeriod() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, NOW.plusMinutes(1));
        stubLoaded(a);
        stubSave(a);

        // Act
        service.cancelAppointmentAsDoctor(a.getId(), doctorId);

        // Assert
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancel_appointmentThatAlreadyStarted_isAllowed() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, NOW.minusDays(3));
        stubLoaded(a);
        stubSave(a);

        // Act
        service.cancelAppointmentAsDoctor(a.getId(), doctorId);

        // Assert
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancel_alreadyCancelled_isANoOpWithoutSaveOrEvent() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CANCELLED, CURRENT_SLOT);
        stubLoaded(a);
        when(appointmentMapper.toResponse(a)).thenReturn(AppointmentResponse.builder().build());

        // Act
        service.cancelAppointmentAsDoctor(a.getId(), doctorId);

        // Assert
        verify(appointmentRepository, never()).save(any());
        assertNothingPublished();
    }

    @Test
    void cancel_anotherDoctorsAppointment_throwsAccessDenied() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.cancelAppointmentAsDoctor(a.getId(), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertNothingPublished();
    }

    @Test
    void cancel_unknownAppointment_throwsNotFound() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.cancelAppointmentAsDoctor(id, doctorId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    // -- change ------------------------------------------------------------------

    @Test
    void change_typeOnly_publishesChangedByDoctorWithPreviousAndNewType() throws Exception {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId);

        // Assert
        assertThat(a.getType()).isEqualTo(FOLLOW_UP);
        JsonNode event = publishedEvent("appointment.changed");
        assertThat(event.get("changedBy").asText()).isEqualTo("DOCTOR");
        assertThat(event.get("type").asText()).isEqualTo("FOLLOW_UP");
        assertThat(event.get("previousType").asText()).isEqualTo("INITIAL_CONSULTATION");
        verify(validDoctorRepository, never()).findByIdForShare(any());
    }

    @Test
    void change_timeOnly_movesTheAppointmentKeepsItsStatusAndPublishesPreviousTime() throws Exception {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);
        stubSaveAndFlush(a);
        LocalDateTime newTime = LocalDateTime.of(2026, 8, 11, 14, 30);

        // Act
        service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(newTime).build(), doctorId);

        // Assert
        assertThat(a.getDateTime()).isEqualTo(newTime);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        JsonNode event = publishedEvent("appointment.changed");
        assertThat(event.get("dateTime").asText()).isEqualTo("2026-08-11T14:30:00");
        assertThat(event.get("previousDateTime").asText()).isEqualTo("2026-08-10T10:00:00");
    }

    @Test
    void change_typeAndTime_changesBoth() throws Exception {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(), DoctorAppointmentUpdateRequest.builder()
                .type(FOLLOW_UP).dateTime(LocalDateTime.of(2026, 8, 11, 9, 0)).build(), doctorId);

        // Assert
        assertThat(a.getType()).isEqualTo(FOLLOW_UP);
        assertThat(a.getDateTime()).isEqualTo(LocalDateTime.of(2026, 8, 11, 9, 0));
        publishedEvent("appointment.changed");
    }

    @Test
    void change_leavesThePatientsNotesUntouched() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId);

        // Assert
        assertThat(a.getNotes()).isEqualTo("patient notes");
    }

    @Test
    void change_sameTimeAndType_publishesNothing() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(), DoctorAppointmentUpdateRequest.builder()
                .type(INITIAL_CONSULTATION).dateTime(CURRENT_SLOT).build(), doctorId);

        // Assert
        assertNothingPublished();
        verify(validDoctorRepository, never()).findByIdForShare(any());
    }

    @Test
    void change_appointmentStartingInTenMinutes_isAllowedWithoutNoticePeriod() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, NOW.plusMinutes(10));
        stubLoaded(a);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId);

        // Assert
        assertThat(a.getType()).isEqualTo(FOLLOW_UP);
    }

    @Test
    void change_toANewTime_clearsTheConflictFlag() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        a.setConflictReason(ConflictReason.DOCTOR_VACATION);
        stubLoaded(a);
        stubDoctor(false);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(), DoctorAppointmentUpdateRequest.builder()
                .dateTime(LocalDateTime.of(2026, 8, 12, 11, 0)).build(), doctorId);

        // Assert
        assertThat(a.getConflictReason()).isNull();
    }

    @Test
    void change_typeOnly_keepsTheConflictFlag() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        a.setConflictReason(ConflictReason.OUTSIDE_OPENING_HOURS);
        stubLoaded(a);
        stubSaveAndFlush(a);

        // Act
        service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId);

        // Assert
        assertThat(a.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void change_cancelledAppointment_throwsNotChangeable() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CANCELLED, CURRENT_SLOT);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId))
                .isInstanceOf(AppointmentNotChangeableException.class);
        assertNothingPublished();
    }

    @Test
    void change_anotherDoctorsAppointment_throwsAccessDenied() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);
        assertThat(a.getType()).isEqualTo(INITIAL_CONSULTATION);
    }

    @Test
    void change_unknownAppointment_throwsNotFound() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(id,
                DoctorAppointmentUpdateRequest.builder().type(FOLLOW_UP).build(), doctorId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void change_newTimeAlreadyTaken_throwsSlotAlreadyBookedAndPublishesNothing() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);
        when(appointmentRepository.saveAndFlush(a)).thenThrow(new DataIntegrityViolationException("ux_active_appointment"));

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(LocalDateTime.of(2026, 8, 11, 9, 0)).build(), doctorId))
                .isInstanceOf(SlotAlreadyBookedException.class);
        assertNothingPublished();
    }

    @Test
    void change_newTimeInThePast_throwsSlotInPast() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(NOW.minusMinutes(30)).build(), doctorId))
                .isInstanceOf(SlotInPastException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void change_newTimeNotOnTheSlotGrid_throwsSlotNotAligned() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(LocalDateTime.of(2026, 8, 11, 9, 10)).build(), doctorId))
                .isInstanceOf(SlotNotAlignedException.class);
    }

    @Test
    void change_newTimeOutsideOpeningHours_throwsOutsideOpeningHours() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(false);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(LocalDateTime.of(2026, 8, 11, 18, 0)).build(), doctorId))
                .isInstanceOf(OutsideOpeningHoursException.class);
    }

    @Test
    void change_newTimeOnAVacationDay_throwsSlotOnVacation() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        stubDoctor(true);

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(LocalDateTime.of(2026, 8, 20, 9, 0)).build(), doctorId))
                .isInstanceOf(SlotOnVacationException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void change_doctorMissingFromTheReadModel_throwsDoctorNotFound() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        stubLoaded(a);
        when(validDoctorRepository.findByIdForShare(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.updateAppointmentAsDoctor(a.getId(),
                DoctorAppointmentUpdateRequest.builder().dateTime(LocalDateTime.of(2026, 8, 11, 9, 0)).build(), doctorId))
                .isInstanceOf(DoctorNotFoundException.class);
    }

    // -- list --------------------------------------------------------------------

    @Test
    void list_mapsPatientNameAndEmailFromTheReadModelAndReturnsTheFlag() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.PENDING, CURRENT_SLOT);
        a.setConflictReason(ConflictReason.DOCTOR_VACATION);
        when(appointmentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(a));
        when(validPatientRepository.findAllById(List.of(patientId))).thenReturn(List.of(ValidPatient.builder()
                .patientId(patientId).firstName("Jane").lastName("Doe").email("jane@example.com").build()));

        // Act
        List<DoctorAppointmentResponse> result = service.getAppointmentsForDoctor(doctorId, null, null, null);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPatientName()).isEqualTo("Jane Doe");
        assertThat(result.get(0).getPatientEmail()).isEqualTo("jane@example.com");
        assertThat(result.get(0).getConflictReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
        assertThat(result.get(0).getStatus()).isEqualTo(AppointmentStatus.PENDING);
    }

    @Test
    void list_patientMissingFromTheReadModel_returnsNullNames() {
        // Arrange
        Appointment a = appointment(AppointmentStatus.CONFIRMED, CURRENT_SLOT);
        when(appointmentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(a));
        when(validPatientRepository.findAllById(any())).thenReturn(List.of());

        // Act
        List<DoctorAppointmentResponse> result = service.getAppointmentsForDoctor(doctorId, null, null, null);

        // Assert
        assertThat(result.get(0).getPatientName()).isNull();
        assertThat(result.get(0).getPatientEmail()).isNull();
        assertThat(result.get(0).getPatientId()).isEqualTo(patientId);
    }

    @Test
    void list_sortsByDateTimeAscending() {
        // Arrange
        when(appointmentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(validPatientRepository.findAllById(any())).thenReturn(List.of());

        // Act
        service.getAppointmentsForDoctor(doctorId, null, null, null);

        // Assert
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(appointmentRepository).findAll(any(Specification.class), sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by("dateTime").ascending());
    }

    @Test
    void list_fromAfterTo_throwsInvalidDateRange() {
        // Act & Assert
        assertThatThrownBy(() -> service.getAppointmentsForDoctor(doctorId,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 10), null))
                .isInstanceOf(InvalidDateRangeException.class);
        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void list_fromEqualToTo_isAccepted() {
        // Arrange
        when(appointmentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(validPatientRepository.findAllById(any())).thenReturn(List.of());

        // Act
        List<DoctorAppointmentResponse> result = service.getAppointmentsForDoctor(doctorId,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10), null);

        // Assert
        assertThat(result).isEmpty();
    }
}
