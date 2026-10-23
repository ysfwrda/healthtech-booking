package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.DoctorVacation;
import com.healthtech.doctor.domain.OpeningHours;
import com.healthtech.doctor.dto.AvailabilityResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.dto.VacationDto;
import com.healthtech.doctor.dto.VacationRequest;
import com.healthtech.doctor.event.DomainEventPublisher;
import com.healthtech.doctor.event.DoctorAvailabilityUpdated;
import com.healthtech.doctor.event.OpeningHoursData;
import com.healthtech.doctor.event.VacationData;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.exception.InvalidVacationException;
import com.healthtech.doctor.exception.VacationNotFoundException;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.DoctorVacationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// A doctor's weekly opening hours and vacations. A write is never refused because of appointments
// already booked: appointment-service reacts to doctor.availability-updated and flags the ones
// that no longer fit.
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final DoctorRepository doctorRepository;
    private final DoctorVacationRepository vacationRepository;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException(doctorId));
        return toResponse(doctor);
    }

    @Transactional
    public AvailabilityResponse replaceOpeningHours(UUID doctorId, Set<OpeningHoursDto> openingHours) {
        Doctor doctor = lockDoctor(doctorId);
        Set<OpeningHours> replacement = openingHours.stream()
                .map(dto -> OpeningHours.builder()
                        .dayOfWeek(dto.getDayOfWeek())
                        .startTime(dto.getStartTime())
                        .endTime(dto.getEndTime())
                        .build())
                .collect(Collectors.toCollection(HashSet::new));
        doctor.getOpeningHours().clear();
        doctor.getOpeningHours().addAll(replacement);
        publishSnapshot(doctor);
        log.info("Opening hours replaced, doctorId {}", doctorId);
        return toResponse(doctor);
    }

    @Transactional
    public VacationDto addVacation(UUID doctorId, VacationRequest request) {
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new InvalidVacationException("startDate must not be after endDate");
        }
        if (request.getEndDate().isBefore(LocalDate.now(clock))) {
            throw new InvalidVacationException("endDate must not be in the past");
        }
        Doctor doctor = lockDoctor(doctorId);
        DoctorVacation saved = vacationRepository.save(DoctorVacation.builder()
                .doctorId(doctorId)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .build());
        publishSnapshot(doctor);
        log.info("Vacation added, doctorId {}, vacationId {}", doctorId, saved.getId());
        return toDto(saved);
    }

    @Transactional
    public void removeVacation(UUID doctorId, UUID vacationId) {
        Doctor doctor = lockDoctor(doctorId);
        DoctorVacation vacation = vacationRepository.findByIdAndDoctorId(vacationId, doctorId)
                .orElseThrow(() -> new VacationNotFoundException(vacationId));
        vacationRepository.delete(vacation);
        publishSnapshot(doctor);
        log.info("Vacation removed, doctorId {}, vacationId {}", doctorId, vacationId);
    }

    // The row lock serializes one doctor's edits, so versions are consecutive and match commit order.
    private Doctor lockDoctor(UUID doctorId) {
        return doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException(doctorId));
    }

    private void publishSnapshot(Doctor doctor) {
        doctor.setAvailabilityVersion(doctor.getAvailabilityVersion() + 1);
        doctorRepository.saveAndFlush(doctor);
        DoctorAvailabilityUpdated event = DoctorAvailabilityUpdated.builder()
                .eventId(UUID.randomUUID())
                .doctorId(doctor.getId())
                .openingHours(OpeningHoursData.fromAll(doctor.getOpeningHours()))
                .vacations(currentVacations(doctor.getId()).stream()
                        .map(v -> VacationData.builder().startDate(v.getStartDate()).endDate(v.getEndDate()).build())
                        .toList())
                .version(doctor.getAvailabilityVersion())
                .build();
        eventPublisher.publish("doctor.availability-updated", doctor.getId(), event.getEventId(), event);
    }

    private List<DoctorVacation> currentVacations(UUID doctorId) {
        return vacationRepository.findByDoctorIdAndEndDateGreaterThanEqualOrderByStartDateAsc(
                doctorId, LocalDate.now(clock));
    }

    private AvailabilityResponse toResponse(Doctor doctor) {
        Set<OpeningHoursDto> hours = doctor.getOpeningHours() == null ? Set.of() :
                doctor.getOpeningHours().stream()
                        .map(oh -> OpeningHoursDto.builder()
                                .dayOfWeek(oh.getDayOfWeek())
                                .startTime(oh.getStartTime())
                                .endTime(oh.getEndTime())
                                .build())
                        .collect(Collectors.toSet());
        return AvailabilityResponse.builder()
                .openingHours(hours)
                .vacations(currentVacations(doctor.getId()).stream().map(this::toDto).toList())
                .build();
    }

    private VacationDto toDto(DoctorVacation vacation) {
        return VacationDto.builder()
                .id(vacation.getId())
                .startDate(vacation.getStartDate())
                .endDate(vacation.getEndDate())
                .build();
    }
}
