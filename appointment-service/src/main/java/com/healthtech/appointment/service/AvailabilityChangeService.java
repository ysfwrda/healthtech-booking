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
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.VacationPeriod;
import com.healthtech.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Applies a doctor's new availability to the read model, then flags the booked appointments that no longer fit.
// The two steps use separate transactions on purpose: the doctor row is locked only while the read model is
// replaced, never while appointments are locked, so this cannot deadlock with a patient or doctor change
// (which locks the appointment first and then reads the doctor).
@Service
@RequiredArgsConstructor
public class AvailabilityChangeService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityChangeService.class);

    private final ValidDoctorRepository validDoctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final DomainEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final SlotPolicy slotPolicy;
    private final Clock clock;

    // Not @Transactional: see the class comment.
    public void handle(DoctorAvailabilityUpdated event) {
        UUID doctorId = event.getDoctorId();
        transactionOperations.executeWithoutResult(status -> applyToReadModel(event));

        // Runs for every delivery, also a stale or repeated one: it reads the current read model and only
        // acts on appointments whose flag has to change, so repeating it is harmless and finishes an
        // earlier run that stopped part-way.
        List<UUID> appointmentIds = appointmentRepository.findIdsByDoctorFromExcludingStatus(
                doctorId, LocalDateTime.now(clock), AppointmentStatus.CANCELLED);
        int failed = 0;
        for (UUID appointmentId : appointmentIds) {
            try {
                transactionOperations.executeWithoutResult(status -> reevaluate(appointmentId));
            } catch (RuntimeException e) {
                failed++;
                log.error("Could not re-evaluate appointmentId {} after an availability change of doctorId {}",
                        appointmentId, doctorId, e);
            }
        }
        if (failed > 0) {
            throw new IllegalStateException(failed + " appointment(s) of doctorId " + doctorId
                    + " could not be re-evaluated");
        }
    }

    private void applyToReadModel(DoctorAvailabilityUpdated event) {
        Optional<ValidDoctor> locked = validDoctorRepository.findByIdForUpdate(event.getDoctorId());
        // A doctor this service has not heard of yet: create the row; doctor.registered fills in the names.
        ValidDoctor doctor = locked.orElseGet(() -> ValidDoctor.builder().doctorId(event.getDoctorId()).build());
        if (doctor.getAvailabilityVersion() != null && event.getVersion() <= doctor.getAvailabilityVersion()) {
            log.info("Ignoring availability version {} for doctorId {}, version {} is already applied",
                    event.getVersion(), event.getDoctorId(), doctor.getAvailabilityVersion());
            return;
        }
        Set<OpeningHours> hours = event.getOpeningHours() == null ? Set.of() :
                event.getOpeningHours().stream().map(this::toOpeningHours).collect(Collectors.toSet());
        Set<VacationPeriod> vacations = event.getVacations() == null ? Set.of() :
                event.getVacations().stream().map(this::toVacation).collect(Collectors.toSet());
        doctor.setOpeningHours(new HashSet<>(hours));
        doctor.setVacations(new HashSet<>(vacations));
        doctor.setAvailabilityVersion(event.getVersion());
        validDoctorRepository.save(doctor);
        log.info("Applied availability version {} for doctorId {}", event.getVersion(), event.getDoctorId());
    }

    private void reevaluate(UUID appointmentId) {
        Optional<Appointment> locked = appointmentRepository.findByIdForUpdate(appointmentId);
        if (locked.isEmpty()) {
            return;
        }
        Appointment appointment = locked.get();
        LocalDateTime now = LocalDateTime.now(clock);
        // Re-read under the appointment lock: a cancel or change may have committed since the id was listed.
        if (appointment.getStatus() == AppointmentStatus.CANCELLED || appointment.getDateTime().isBefore(now)) {
            return;
        }
        Optional<ValidDoctor> doctor = validDoctorRepository.findById(appointment.getDoctorId());
        if (doctor.isEmpty()) {
            return;
        }
        ConflictReason reason = conflictFor(appointment, doctor.get());
        if (reason == appointment.getConflictReason()) {
            return;
        }
        appointment.setConflictReason(reason);
        appointmentRepository.save(appointment);
        if (reason != null) {
            AppointmentRescheduleRequired event = AppointmentRescheduleRequired.builder()
                    .eventId(UUID.randomUUID())
                    .appointmentId(appointment.getId())
                    .patientId(appointment.getPatientId())
                    .doctorId(appointment.getDoctorId())
                    .duration(appointment.getDuration())
                    .dateTime(appointment.getDateTime())
                    .reason(reason)
                    .detectedAt(now)
                    .build();
            eventPublisher.publish("appointment.reschedule-required", appointment.getId(), event.getEventId(), event);
            log.info("Appointment flagged {}, appointmentId {}", reason, appointment.getId());
        } else {
            log.info("Appointment fits the doctor's availability again, appointmentId {}", appointment.getId());
        }
    }

    // A vacation day wins over opening hours when both apply.
    private ConflictReason conflictFor(Appointment appointment, ValidDoctor doctor) {
        LocalDateTime start = appointment.getDateTime();
        if (doctor.isOnVacation(start.toLocalDate())) {
            return ConflictReason.DOCTOR_VACATION;
        }
        LocalTime from = start.toLocalTime();
        if (!doctor.isOpenFor(start.getDayOfWeek(), from, slotPolicy.slotEnd(from))) {
            return ConflictReason.OUTSIDE_OPENING_HOURS;
        }
        return null;
    }

    private OpeningHours toOpeningHours(OpeningHoursPayload payload) {
        return OpeningHours.builder()
                .dayOfWeek(payload.getDayOfWeek())
                .startTime(payload.getStartTime())
                .endTime(payload.getEndTime())
                .build();
    }

    private VacationPeriod toVacation(VacationPayload payload) {
        return VacationPeriod.builder().startDate(payload.getStartDate()).endDate(payload.getEndDate()).build();
    }
}
