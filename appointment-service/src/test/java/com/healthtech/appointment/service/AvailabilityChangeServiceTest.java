package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.ConflictReason;
import com.healthtech.appointment.event.AppointmentRescheduleRequired;
import com.healthtech.appointment.event.DoctorAvailabilityUpdated;
import com.healthtech.appointment.event.DomainEventPublisher;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.event.VacationPayload;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.VacationPeriod;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.healthtech.appointment.domain.AppointmentType.INITIAL_CONSULTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityChangeServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);
    // 2026-08-10 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    @Mock private ValidDoctorRepository validDoctorRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private DomainEventPublisher eventPublisher;

    private final UUID doctorId = UUID.randomUUID();
    private AvailabilityChangeService service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityChangeService(validDoctorRepository, appointmentRepository, eventPublisher,
                TransactionOperations.withoutTransaction(), new SlotPolicy(),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private static OpeningHoursPayload hours(DayOfWeek day, int startHour, int endHour) {
        return OpeningHoursPayload.builder().dayOfWeek(day)
                .startTime(LocalTime.of(startHour, 0)).endTime(LocalTime.of(endHour, 0)).build();
    }

    private static OpeningHours stored(DayOfWeek day, int startHour, int endHour) {
        return OpeningHours.builder().dayOfWeek(day)
                .startTime(LocalTime.of(startHour, 0)).endTime(LocalTime.of(endHour, 0)).build();
    }

    private DoctorAvailabilityUpdated event(long version, Set<OpeningHoursPayload> hours, List<VacationPayload> vacations) {
        return DoctorAvailabilityUpdated.builder().eventId(UUID.randomUUID()).doctorId(doctorId)
                .openingHours(hours).vacations(vacations).version(version).build();
    }

    private ValidDoctor storedDoctor(Long version) {
        return ValidDoctor.builder().doctorId(doctorId).firstName("Anna").lastName("Weber")
                .openingHours(new HashSet<>(Set.of(stored(DayOfWeek.MONDAY, 9, 17))))
                .vacations(new HashSet<>())
                .availabilityVersion(version)
                .build();
    }

    private Appointment appointmentAt(LocalDateTime dateTime) {
        return Appointment.builder().id(UUID.randomUUID()).patientId(UUID.randomUUID()).doctorId(doctorId)
                .dateTime(dateTime).duration(30).type(INITIAL_CONSULTATION).status(AppointmentStatus.CONFIRMED).build();
    }

    // The doctor row as the apply step finds it, and the same doctor as the re-evaluation reads it afterwards.
    private void stubDoctorAfterApply(ValidDoctor doctor) {
        when(validDoctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.of(doctor));
        lenient().when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
    }

    private void stubListed(Appointment... appointments) {
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(doctorId, NOW, AppointmentStatus.CANCELLED))
                .thenReturn(java.util.Arrays.stream(appointments).map(Appointment::getId).toList());
        for (Appointment a : appointments) {
            when(appointmentRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        }
    }

    private AppointmentRescheduleRequired publishedEvent() {
        ArgumentCaptor<AppointmentRescheduleRequired> captor = ArgumentCaptor.forClass(AppointmentRescheduleRequired.class);
        verify(eventPublisher).publish(eq("appointment.reschedule-required"), any(UUID.class), any(UUID.class), captor.capture());
        return captor.getValue();
    }

    // -- applying the snapshot ---------------------------------------------------

    @Test
    void handle_unknownDoctor_createsTheRowFromTheSnapshot() {
        // Arrange
        when(validDoctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.empty());
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(any(), any(), any())).thenReturn(List.of());

        // Act
        service.handle(event(3, Set.of(hours(DayOfWeek.TUESDAY, 8, 12)),
                List.of(VacationPayload.builder().startDate(MONDAY).endDate(MONDAY.plusDays(1)).build())));

        // Assert
        ArgumentCaptor<ValidDoctor> saved = ArgumentCaptor.forClass(ValidDoctor.class);
        verify(validDoctorRepository).save(saved.capture());
        assertThat(saved.getValue().getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getValue().getAvailabilityVersion()).isEqualTo(3);
        assertThat(saved.getValue().getOpeningHours()).hasSize(1);
        assertThat(saved.getValue().getVacations()).hasSize(1);
        assertThat(saved.getValue().isAutoConfirmBookings()).isFalse();
    }

    @Test
    void handle_newerVersion_replacesHoursAndVacationsAndStoresTheVersion() {
        // Arrange
        ValidDoctor doctor = storedDoctor(2L);
        stubDoctorAfterApply(doctor);
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(any(), any(), any())).thenReturn(List.of());

        // Act
        service.handle(event(3, Set.of(hours(DayOfWeek.FRIDAY, 10, 14)),
                List.of(VacationPayload.builder().startDate(MONDAY).endDate(MONDAY).build())));

        // Assert
        verify(validDoctorRepository).save(doctor);
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(3);
        assertThat(doctor.getOpeningHours()).extracting(OpeningHours::getDayOfWeek).containsExactly(DayOfWeek.FRIDAY);
        assertThat(doctor.getVacations()).hasSize(1);
        assertThat(doctor.getFirstName()).isEqualTo("Anna");
    }

    @Test
    void handle_firstVersionForARowWithoutVersion_isApplied() {
        // Arrange
        ValidDoctor doctor = storedDoctor(null);
        stubDoctorAfterApply(doctor);
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(any(), any(), any())).thenReturn(List.of());

        // Act
        service.handle(event(1, Set.of(hours(DayOfWeek.FRIDAY, 10, 14)), List.of()));

        // Assert
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(1);
    }

    @Test
    void handle_sameVersionAgain_changesNothingInTheReadModel() {
        // Arrange
        ValidDoctor doctor = storedDoctor(3L);
        stubDoctorAfterApply(doctor);
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(any(), any(), any())).thenReturn(List.of());

        // Act
        service.handle(event(3, Set.of(hours(DayOfWeek.FRIDAY, 10, 14)), List.of()));

        // Assert
        verify(validDoctorRepository, never()).save(any());
        assertThat(doctor.getOpeningHours()).extracting(OpeningHours::getDayOfWeek).containsExactly(DayOfWeek.MONDAY);
    }

    @Test
    void handle_olderVersion_changesNothingInTheReadModel() {
        // Arrange
        ValidDoctor doctor = storedDoctor(5L);
        stubDoctorAfterApply(doctor);
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(any(), any(), any())).thenReturn(List.of());

        // Act
        service.handle(event(4, Set.of(hours(DayOfWeek.FRIDAY, 10, 14)), List.of()));

        // Assert
        verify(validDoctorRepository, never()).save(any());
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(5);
    }

    @Test
    void handle_staleEvent_stillReEvaluatesAgainstTheCurrentReadModel() {
        // Arrange: version 5 is applied; the redelivered version 4 must still finish an earlier, interrupted run
        ValidDoctor doctor = storedDoctor(5L);
        doctor.setOpeningHours(new HashSet<>(Set.of(stored(DayOfWeek.MONDAY, 9, 12))));
        stubDoctorAfterApply(doctor);
        Appointment afternoon = appointmentAt(MONDAY.atTime(14, 0));
        stubListed(afternoon);

        // Act
        service.handle(event(4, Set.of(hours(DayOfWeek.MONDAY, 9, 17)), List.of()));

        // Assert
        assertThat(afternoon.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
        publishedEvent();
    }

    // -- flagging ----------------------------------------------------------------

    @Test
    void handle_appointmentOutsideTheNewHours_isFlaggedSavedAndAnnounced() {
        // Arrange
        ValidDoctor doctor = storedDoctor(1L);
        stubDoctorAfterApply(doctor);
        Appointment afternoon = appointmentAt(MONDAY.atTime(14, 0));
        stubListed(afternoon);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of()));

        // Assert
        assertThat(afternoon.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
        assertThat(afternoon.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository).save(afternoon);
        AppointmentRescheduleRequired announced = publishedEvent();
        assertThat(announced.getAppointmentId()).isEqualTo(afternoon.getId());
        assertThat(announced.getPatientId()).isEqualTo(afternoon.getPatientId());
        assertThat(announced.getDoctorId()).isEqualTo(doctorId);
        assertThat(announced.getDateTime()).isEqualTo(MONDAY.atTime(14, 0));
        assertThat(announced.getReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
        assertThat(announced.getDetectedAt()).isEqualTo(NOW);
    }

    @Test
    void handle_appointmentEndingExactlyAtTheNewClosingTime_fitsAndIsNotFlagged() {
        // Arrange: the 11:30 slot ends at 12:00
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment lastSlot = appointmentAt(MONDAY.atTime(11, 30));
        stubListed(lastSlot);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of()));

        // Assert
        assertThat(lastSlot.getConflictReason()).isNull();
        verify(appointmentRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void handle_appointmentOneSlotAfterTheNewClosingTime_isFlagged() {
        // Arrange: the 12:00 slot starts at the new closing time
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment slot = appointmentAt(MONDAY.atTime(12, 0));
        stubListed(slot);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of()));

        // Assert
        assertThat(slot.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void handle_appointmentOnADayWithoutOpeningHours_isFlagged() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment tuesday = appointmentAt(MONDAY.plusDays(1).atTime(10, 0));
        stubListed(tuesday);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)), List.of()));

        // Assert
        assertThat(tuesday.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void handle_emptyOpeningHours_flagsEveryFutureAppointment() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment slot = appointmentAt(MONDAY.atTime(10, 0));
        stubListed(slot);

        // Act
        service.handle(event(2, Set.of(), List.of()));

        // Assert
        assertThat(slot.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void handle_firstAndLastDayOfAVacation_areFlaggedAsVacation() {
        // Arrange: Monday 9-17 open every week; vacation from Monday 2026-08-10 to Monday 2026-08-17
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment first = appointmentAt(MONDAY.atTime(10, 0));
        Appointment last = appointmentAt(MONDAY.plusDays(7).atTime(10, 0));
        stubListed(first, last);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)),
                List.of(VacationPayload.builder().startDate(MONDAY).endDate(MONDAY.plusDays(7)).build())));

        // Assert
        assertThat(first.getConflictReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
        assertThat(last.getConflictReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
    }

    @Test
    void handle_dayBeforeAndDayAfterAVacation_areNotFlagged() {
        // Arrange: vacation on the Monday 2026-08-17 only; appointments on the Mondays before and after
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment before = appointmentAt(MONDAY.atTime(10, 0));
        Appointment after = appointmentAt(MONDAY.plusDays(14).atTime(10, 0));
        stubListed(before, after);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)),
                List.of(VacationPayload.builder().startDate(MONDAY.plusDays(7)).endDate(MONDAY.plusDays(7)).build())));

        // Assert
        assertThat(before.getConflictReason()).isNull();
        assertThat(after.getConflictReason()).isNull();
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void handle_vacationAndOutsideHoursAtOnce_vacationWins() {
        // Arrange: a Tuesday has no opening hours and is also on vacation
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment tuesday = appointmentAt(MONDAY.plusDays(1).atTime(10, 0));
        stubListed(tuesday);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)),
                List.of(VacationPayload.builder().startDate(MONDAY.plusDays(1)).endDate(MONDAY.plusDays(1)).build())));

        // Assert
        assertThat(tuesday.getConflictReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
        assertThat(publishedEvent().getReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
    }

    @Test
    void handle_alreadyFlaggedWithTheSameReason_savesAndAnnouncesNothing() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment flagged = appointmentAt(MONDAY.atTime(14, 0));
        flagged.setConflictReason(ConflictReason.OUTSIDE_OPENING_HOURS);
        stubListed(flagged);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of()));

        // Assert
        verify(appointmentRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void handle_flaggedAppointmentThatFitsAgain_isClearedWithoutAnnouncement() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment flagged = appointmentAt(MONDAY.atTime(14, 0));
        flagged.setConflictReason(ConflictReason.OUTSIDE_OPENING_HOURS);
        stubListed(flagged);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)), List.of()));

        // Assert
        assertThat(flagged.getConflictReason()).isNull();
        verify(appointmentRepository).save(flagged);
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void handle_flagChangesFromHoursToVacation_announcesTheNewReason() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment flagged = appointmentAt(MONDAY.atTime(14, 0));
        flagged.setConflictReason(ConflictReason.OUTSIDE_OPENING_HOURS);
        stubListed(flagged);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 17)),
                List.of(VacationPayload.builder().startDate(MONDAY).endDate(MONDAY).build())));

        // Assert
        assertThat(flagged.getConflictReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
        assertThat(publishedEvent().getReason()).isEqualTo(ConflictReason.DOCTOR_VACATION);
    }

    @Test
    void handle_appointmentCancelledAfterItWasListed_isSkippedNotRevived() {
        // Arrange: the status is re-read under the appointment lock
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment cancelled = appointmentAt(MONDAY.atTime(14, 0));
        cancelled.setStatus(AppointmentStatus.CANCELLED);
        stubListed(cancelled);

        // Act
        service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of()));

        // Assert
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getConflictReason()).isNull();
        verify(appointmentRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void handle_appointmentThatStartedAfterItWasListed_isSkipped() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment started = appointmentAt(NOW.minusMinutes(1));
        stubListed(started);

        // Act
        service.handle(event(2, Set.of(), List.of()));

        // Assert
        assertThat(started.getConflictReason()).isNull();
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void handle_appointmentDeletedAfterItWasListed_isSkipped() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        UUID gone = UUID.randomUUID();
        when(appointmentRepository.findIdsByDoctorFromExcludingStatus(doctorId, NOW, AppointmentStatus.CANCELLED))
                .thenReturn(List.of(gone));
        when(appointmentRepository.findByIdForUpdate(gone)).thenReturn(Optional.empty());

        // Act
        service.handle(event(2, Set.of(), List.of()));

        // Assert
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void handle_oneAppointmentFails_theOthersAreStillFlaggedAndTheHandlerThrows() {
        // Arrange
        stubDoctorAfterApply(storedDoctor(1L));
        Appointment broken = appointmentAt(MONDAY.atTime(13, 0));
        Appointment fine = appointmentAt(MONDAY.atTime(14, 0));
        stubListed(broken, fine);
        doThrow(new IllegalStateException("lock timeout")).when(appointmentRepository).save(broken);

        // Act & Assert
        assertThatThrownBy(() -> service.handle(event(2, Set.of(hours(DayOfWeek.MONDAY, 9, 12)), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 appointment(s)");
        assertThat(fine.getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
        verify(appointmentRepository).save(fine);
    }

    @Test
    void handle_doctorRowMissingDuringReEvaluation_skipsTheAppointment() {
        // Arrange
        when(validDoctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.of(storedDoctor(1L)));
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.empty());
        Appointment slot = appointmentAt(MONDAY.atTime(14, 0));
        stubListed(slot);

        // Act
        service.handle(event(2, Set.of(), List.of()));

        // Assert
        verify(appointmentRepository, never()).save(any());
    }
}
