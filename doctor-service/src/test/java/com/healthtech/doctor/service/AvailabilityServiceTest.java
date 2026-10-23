package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.DoctorVacation;
import com.healthtech.doctor.domain.OpeningHours;
import com.healthtech.doctor.dto.AvailabilityResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.dto.VacationDto;
import com.healthtech.doctor.dto.VacationRequest;
import com.healthtech.doctor.event.DoctorAvailabilityUpdated;
import com.healthtech.doctor.event.DomainEventPublisher;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.exception.InvalidVacationException;
import com.healthtech.doctor.exception.VacationNotFoundException;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.DoctorVacationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Mock private DoctorRepository doctorRepository;
    @Mock private DoctorVacationRepository vacationRepository;
    @Mock private DomainEventPublisher eventPublisher;

    private final UUID doctorId = UUID.randomUUID();
    private Doctor doctor;
    private AvailabilityService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC);
        service = new AvailabilityService(doctorRepository, vacationRepository, eventPublisher, clock);
        doctor = Doctor.builder()
                .id(doctorId)
                .openingHours(new HashSet<>(Set.of(OpeningHours.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build())))
                .availabilityVersion(4)
                .build();
    }

    private void stubDoctorLocked() {
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.of(doctor));
    }

    private void stubVacations(DoctorVacation... vacations) {
        when(vacationRepository.findByDoctorIdAndEndDateGreaterThanEqualOrderByStartDateAsc(doctorId, TODAY))
                .thenReturn(List.of(vacations));
    }

    private OpeningHoursDto hours(DayOfWeek day, int startHour, int endHour) {
        return OpeningHoursDto.builder().dayOfWeek(day)
                .startTime(LocalTime.of(startHour, 0)).endTime(LocalTime.of(endHour, 0)).build();
    }

    private DoctorVacation vacation(LocalDate start, LocalDate end) {
        return DoctorVacation.builder().id(UUID.randomUUID()).doctorId(doctorId)
                .startDate(start).endDate(end).build();
    }

    private DoctorAvailabilityUpdated publishedEvent() {
        ArgumentCaptor<DoctorAvailabilityUpdated> captor = ArgumentCaptor.forClass(DoctorAvailabilityUpdated.class);
        verify(eventPublisher).publish(eq("doctor.availability-updated"), eq(doctorId), any(UUID.class), captor.capture());
        return captor.getValue();
    }

    // -- getAvailability ---------------------------------------------------------

    @Test
    void getAvailability_returnsHoursAndVacationsThatHaveNotEnded() {
        // Arrange
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        DoctorVacation upcoming = vacation(TODAY.plusDays(10), TODAY.plusDays(12));
        stubVacations(upcoming);

        // Act
        AvailabilityResponse response = service.getAvailability(doctorId);

        // Assert
        assertThat(response.getOpeningHours()).hasSize(1);
        OpeningHoursDto monday = response.getOpeningHours().iterator().next();
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(monday.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(response.getVacations()).hasSize(1);
        assertThat(response.getVacations().get(0).getId()).isEqualTo(upcoming.getId());
        assertThat(response.getVacations().get(0).getStartDate()).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void getAvailability_unknownDoctor_throwsDoctorNotFound() {
        // Arrange
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.getAvailability(doctorId)).isInstanceOf(DoctorNotFoundException.class);
    }

    // -- replaceOpeningHours -----------------------------------------------------

    @Test
    void replaceOpeningHours_replacesBlocksBumpsVersionAndPublishesSnapshot() {
        // Arrange
        stubDoctorLocked();
        stubVacations(vacation(TODAY.plusDays(3), TODAY.plusDays(4)));

        // Act
        AvailabilityResponse response = service.replaceOpeningHours(doctorId,
                Set.of(hours(DayOfWeek.TUESDAY, 10, 12), hours(DayOfWeek.TUESDAY, 13, 16)));

        // Assert
        assertThat(response.getOpeningHours()).hasSize(2)
                .allMatch(h -> h.getDayOfWeek() == DayOfWeek.TUESDAY);
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(5);
        verify(doctorRepository).saveAndFlush(doctor);
        DoctorAvailabilityUpdated event = publishedEvent();
        assertThat(event.getVersion()).isEqualTo(5);
        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getOpeningHours()).hasSize(2);
        assertThat(event.getVacations()).hasSize(1);
        assertThat(event.getVacations().get(0).getStartDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void replaceOpeningHours_emptySet_isAcceptedAndPublishesEmptyHours() {
        // Arrange
        stubDoctorLocked();
        stubVacations();

        // Act
        AvailabilityResponse response = service.replaceOpeningHours(doctorId, Set.of());

        // Assert
        assertThat(response.getOpeningHours()).isEmpty();
        assertThat(publishedEvent().getOpeningHours()).isEmpty();
    }

    @Test
    void replaceOpeningHours_unknownDoctor_throwsAndPublishesNothing() {
        // Arrange
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.replaceOpeningHours(doctorId, Set.of(hours(DayOfWeek.MONDAY, 9, 12))))
                .isInstanceOf(DoctorNotFoundException.class);
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    // -- addVacation -------------------------------------------------------------

    @Test
    void addVacation_validRange_savesBumpsVersionAndPublishesSnapshot() {
        // Arrange
        stubDoctorLocked();
        DoctorVacation saved = vacation(TODAY.plusDays(5), TODAY.plusDays(9));
        when(vacationRepository.save(any(DoctorVacation.class))).thenReturn(saved);
        stubVacations(saved);

        // Act
        VacationDto dto = service.addVacation(doctorId,
                new VacationRequest(TODAY.plusDays(5), TODAY.plusDays(9)));

        // Assert
        assertThat(dto.getId()).isEqualTo(saved.getId());
        assertThat(dto.getStartDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(dto.getEndDate()).isEqualTo(TODAY.plusDays(9));
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(5);
        assertThat(publishedEvent().getVacations()).hasSize(1);
    }

    @Test
    void addVacation_singleDay_isAccepted() {
        // Arrange
        stubDoctorLocked();
        DoctorVacation saved = vacation(TODAY.plusDays(2), TODAY.plusDays(2));
        when(vacationRepository.save(any(DoctorVacation.class))).thenReturn(saved);
        stubVacations(saved);

        // Act
        VacationDto dto = service.addVacation(doctorId, new VacationRequest(TODAY.plusDays(2), TODAY.plusDays(2)));

        // Assert
        assertThat(dto.getStartDate()).isEqualTo(dto.getEndDate());
    }

    @Test
    void addVacation_startAfterEnd_throwsInvalidVacation() {
        // Act & Assert
        assertThatThrownBy(() -> service.addVacation(doctorId,
                new VacationRequest(TODAY.plusDays(3), TODAY.plusDays(2))))
                .isInstanceOf(InvalidVacationException.class)
                .hasMessageContaining("startDate");
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void addVacation_endingToday_isAccepted() {
        // Arrange
        stubDoctorLocked();
        DoctorVacation saved = vacation(TODAY.minusDays(2), TODAY);
        when(vacationRepository.save(any(DoctorVacation.class))).thenReturn(saved);
        stubVacations(saved);

        // Act
        VacationDto dto = service.addVacation(doctorId, new VacationRequest(TODAY.minusDays(2), TODAY));

        // Assert
        assertThat(dto.getEndDate()).isEqualTo(TODAY);
    }

    @Test
    void addVacation_endedYesterday_throwsInvalidVacation() {
        // Act & Assert
        assertThatThrownBy(() -> service.addVacation(doctorId,
                new VacationRequest(TODAY.minusDays(3), TODAY.minusDays(1))))
                .isInstanceOf(InvalidVacationException.class)
                .hasMessageContaining("past");
        verify(vacationRepository, never()).save(any());
    }

    @Test
    void addVacation_unknownDoctor_throwsDoctorNotFound() {
        // Arrange
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.addVacation(doctorId,
                new VacationRequest(TODAY.plusDays(1), TODAY.plusDays(2))))
                .isInstanceOf(DoctorNotFoundException.class);
        verify(vacationRepository, never()).save(any());
    }

    // -- removeVacation ----------------------------------------------------------

    @Test
    void removeVacation_ownVacation_deletesBumpsVersionAndPublishesSnapshotWithoutIt() {
        // Arrange
        stubDoctorLocked();
        DoctorVacation existing = vacation(TODAY.plusDays(5), TODAY.plusDays(6));
        when(vacationRepository.findByIdAndDoctorId(existing.getId(), doctorId)).thenReturn(Optional.of(existing));
        stubVacations();

        // Act
        service.removeVacation(doctorId, existing.getId());

        // Assert
        verify(vacationRepository).delete(existing);
        assertThat(doctor.getAvailabilityVersion()).isEqualTo(5);
        assertThat(publishedEvent().getVacations()).isEmpty();
    }

    @Test
    void removeVacation_otherDoctorsOrUnknownId_throwsVacationNotFoundAndPublishesNothing() {
        // Arrange
        stubDoctorLocked();
        UUID someId = UUID.randomUUID();
        when(vacationRepository.findByIdAndDoctorId(someId, doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.removeVacation(doctorId, someId))
                .isInstanceOf(VacationNotFoundException.class);
        verify(vacationRepository, never()).delete(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void removeVacation_unknownDoctor_throwsDoctorNotFound() {
        // Arrange
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.removeVacation(doctorId, UUID.randomUUID()))
                .isInstanceOf(DoctorNotFoundException.class);
    }
}
