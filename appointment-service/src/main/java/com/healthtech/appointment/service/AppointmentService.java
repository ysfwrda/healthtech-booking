package com.healthtech.appointment.service;

import com.healthtech.appointment.config.ChangePolicyProperties;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.dto.DoctorAppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentChanged;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.event.AppointmentConfirmed;
import com.healthtech.appointment.event.DomainEventPublisher;
import com.healthtech.appointment.event.Initiator;
import com.healthtech.appointment.exception.*;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.*;
import com.healthtech.appointment.repository.AppointmentRepository;
import com.healthtech.appointment.repository.DoctorAppointmentSpecifications;
import com.healthtech.appointment.service.booking.BookingRule;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final ValidPatientRepository validPatientRepository;
    private final ValidDoctorRepository validDoctorRepository;
    private final List<BookingRule> bookingRules;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;
    private final ChangePolicyProperties changePolicy;

    @Transactional
    public AppointmentResponse bookAppointment(AppointmentRequest request, UUID patientId) {
        Appointment appointment = appointmentMapper.toEntity(request);
        appointment.setPatientId(patientId);
        appointment.setDuration(SlotPolicy.SLOT_DURATION_MINUTES);

        ValidPatient patient = validPatientRepository.findById(appointment.getPatientId())
                .orElseThrow(() -> new PatientNotFoundException(appointment.getPatientId()));

        // Shared lock: waits for an availability change in progress, so the rules see the new hours and vacations.
        ValidDoctor doctor = validDoctorRepository.findByIdForShare(appointment.getDoctorId())
                .orElseThrow(() -> new DoctorNotFoundException(appointment.getDoctorId()));

        // A seeded doctor's bookings are confirmed on the spot; every other doctor confirms them.
        appointment.setStatus(doctor.isAutoConfirmBookings() ? AppointmentStatus.CONFIRMED : AppointmentStatus.PENDING);

        bookingRules.forEach(rule -> rule.check(request.getDateTime(), doctor));

        Appointment saved;
        try {
            // saveAndFlush, not save: bookAppointment is now @Transactional, so a plain save()
            // would just enqueue the insert and defer the actual flush to commit time - well
            // after this catch block - letting the constraint violation escape untranslated
            // instead of becoming SlotAlreadyBookedException.
            saved = appointmentRepository.saveAndFlush(appointment);
        }
        catch (DataIntegrityViolationException e) {
            throw new SlotAlreadyBookedException(appointment.getDoctorId(), appointment.getDateTime());
        }

        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(saved.getId())
                .patientId(saved.getPatientId())
                .patientName(patient.getFirstName() + " " + patient.getLastName())
                .patientEmail(patient.getEmail())
                .doctorId(saved.getDoctorId())
                .doctorName(doctor.getFirstName() + " " + doctor.getLastName())
                .type(saved.getType())
                .duration(saved.getDuration())
                .dateTime(saved.getDateTime())
                .bookedAt(saved.getCreatedAt())
                .build();

        eventPublisher.publish("appointment.booked", saved.getId(), event.getEventId(), event);
        log.info("Appointment booked, appointmentId {}, status {}", saved.getId(), saved.getStatus());
        return appointmentMapper.toResponse(saved);
    }

    @Transactional
    public AppointmentResponse cancelAppointment(UUID appointmentId, UUID patientId) {
        Appointment appointment = lockAppointment(appointmentId);
        if(!appointment.getPatientId().equals(patientId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        return cancel(appointment, Initiator.PATIENT);
    }

    @Transactional
    public AppointmentResponse cancelAppointmentAsDoctor(UUID appointmentId, UUID doctorId) {
        Appointment appointment = lockAppointment(appointmentId);
        if (!appointment.getDoctorId().equals(doctorId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        return cancel(appointment, Initiator.DOCTOR);
    }

    @Transactional
    public AppointmentResponse confirmAppointment(UUID appointmentId, UUID doctorId) {
        Appointment appointment = lockAppointment(appointmentId);
        if (!appointment.getDoctorId().equals(doctorId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentNotConfirmableException(appointmentId, "it is cancelled");
        }
        // Idempotent: confirming a confirmed appointment returns the current state without a new save or event.
        if (appointment.getStatus() == AppointmentStatus.CONFIRMED) {
            return appointmentMapper.toResponse(appointment);
        }
        if (appointment.getConflictReason() != null) {
            throw new AppointmentNotConfirmableException(appointmentId,
                    "it no longer fits the doctor's availability, change its time or cancel it");
        }
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        Appointment saved = appointmentRepository.save(appointment);
        AppointmentConfirmed event = AppointmentConfirmed.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(saved.getId())
                .patientId(saved.getPatientId())
                .doctorId(saved.getDoctorId())
                .duration(saved.getDuration())
                .dateTime(saved.getDateTime())
                .confirmedAt(LocalDateTime.now(clock))
                .build();
        eventPublisher.publish("appointment.confirmed", saved.getId(), event.getEventId(), event);
        log.info("Appointment confirmed, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    @Transactional
    public AppointmentResponse updateAppointment(UUID appointmentId, AppointmentUpdateRequest request, UUID patientId) {
        Appointment appointment = lockAppointment(appointmentId);
        if (!appointment.getPatientId().equals(patientId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentNotChangeableException(appointmentId);
        }
        // The notice period protects the doctor from late changes; it does not apply when the doctor's own
        // availability change is what made the appointment unusable.
        if (appointment.getConflictReason() == null) {
            int minNoticeHours = changePolicy.minNoticeHours();
            if (appointment.getDateTime().isBefore(LocalDateTime.now(clock).plusHours(minNoticeHours))) {
                throw new ChangeWindowClosedException(minNoticeHours);
            }
        }
        return applyChange(appointment, request.getType(), request.getDateTime(), request.getNotes(), Initiator.PATIENT);
    }

    @Transactional
    public AppointmentResponse updateAppointmentAsDoctor(UUID appointmentId, DoctorAppointmentUpdateRequest request, UUID doctorId) {
        Appointment appointment = lockAppointment(appointmentId);
        if (!appointment.getDoctorId().equals(doctorId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentNotChangeableException(appointmentId);
        }
        return applyChange(appointment, request.getType(), request.getDateTime(), null, Initiator.DOCTOR);
    }

    public List<AppointmentResponse> getAppointmentsForPatient(UUID patientId) {
        return appointmentRepository.findByPatientIdOrderByDateTimeAsc(patientId).stream()
                .map(appointmentMapper::toResponse)
                .toList();
    }

    // from and to are whole days, both inclusive; any of the three filters may be null.
    @Transactional(readOnly = true)
    public List<DoctorAppointmentResponse> getAppointmentsForDoctor(UUID doctorId, LocalDate from, LocalDate to,
                                                                    Collection<AppointmentStatus> statuses) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDateRangeException();
        }
        List<Appointment> appointments = appointmentRepository.findAll(
                DoctorAppointmentSpecifications.forDoctor(doctorId, from, to, statuses),
                Sort.by("dateTime").ascending());
        Map<UUID, ValidPatient> patients = validPatientRepository
                .findAllById(appointments.stream().map(Appointment::getPatientId).distinct().toList()).stream()
                .collect(Collectors.toMap(ValidPatient::getPatientId, Function.identity()));
        return appointments.stream().map(a -> toDoctorResponse(a, patients.get(a.getPatientId()))).toList();
    }

    private AppointmentResponse cancel(Appointment appointment, Initiator cancelledBy) {
        // Idempotent: a repeated cancel returns the current state without a new save or event.
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return appointmentMapper.toResponse(appointment);
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        AppointmentCancelled event = AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(saved.getId())
                .patientId(saved.getPatientId())
                .doctorId(saved.getDoctorId())
                .duration(saved.getDuration())
                .dateTime(saved.getDateTime())
                .cancelledAt(LocalDateTime.now(clock))
                .cancelledBy(cancelledBy)
                .build();

        eventPublisher.publish("appointment.cancelled", saved.getId(), event.getEventId(), event);
        log.info("Appointment cancelled by {}, appointmentId {}", cancelledBy, saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    // Shared by the patient and the doctor: a null argument means unchanged. notes is null for the doctor.
    private AppointmentResponse applyChange(Appointment appointment, AppointmentType newType, LocalDateTime newDateTime,
                                            String newNotes, Initiator changedBy) {
        AppointmentType previousType = appointment.getType();
        LocalDateTime previousDateTime = appointment.getDateTime();

        if (newDateTime != null && !newDateTime.equals(previousDateTime)) {
            ValidDoctor doctor = validDoctorRepository.findByIdForShare(appointment.getDoctorId())
                    .orElseThrow(() -> new DoctorNotFoundException(appointment.getDoctorId()));
            bookingRules.forEach(rule -> rule.check(newDateTime, doctor));
            appointment.setDateTime(newDateTime);
            // The rules just accepted the new time, so it fits the doctor's availability.
            appointment.setConflictReason(null);
        }
        if (newType != null) {
            appointment.setType(newType);
        }
        if (newNotes != null) {
            appointment.setNotes(newNotes.isEmpty() ? null : newNotes);
        }

        Appointment saved;
        try {
            // saveAndFlush for the same reason as in bookAppointment: the unique-index violation
            // must surface here, not at commit.
            saved = appointmentRepository.saveAndFlush(appointment);
        }
        catch (DataIntegrityViolationException e) {
            throw new SlotAlreadyBookedException(appointment.getDoctorId(), appointment.getDateTime());
        }

        // Notes are not in the event, so a notes-only change publishes nothing.
        if (saved.getType() != previousType || !saved.getDateTime().equals(previousDateTime)) {
            AppointmentChanged event = AppointmentChanged.builder()
                    .eventId(UUID.randomUUID())
                    .appointmentId(saved.getId())
                    .patientId(saved.getPatientId())
                    .doctorId(saved.getDoctorId())
                    .duration(saved.getDuration())
                    .type(saved.getType())
                    .dateTime(saved.getDateTime())
                    .previousType(previousType)
                    .previousDateTime(previousDateTime)
                    .changedAt(LocalDateTime.now(clock))
                    .changedBy(changedBy)
                    .build();
            eventPublisher.publish("appointment.changed", saved.getId(), event.getEventId(), event);
        }
        log.info("Appointment changed by {}, appointmentId {}", changedBy, saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    private Appointment lockAppointment(UUID appointmentId) {
        return appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found: " + appointmentId));
    }

    private DoctorAppointmentResponse toDoctorResponse(Appointment a, ValidPatient patient) {
        return DoctorAppointmentResponse.builder()
                .id(a.getId())
                .patientId(a.getPatientId())
                .patientName(patient == null ? null : patient.getFirstName() + " " + patient.getLastName())
                .patientEmail(patient == null ? null : patient.getEmail())
                .doctorId(a.getDoctorId())
                .dateTime(a.getDateTime())
                .duration(a.getDuration())
                .type(a.getType())
                .status(a.getStatus())
                .conflictReason(a.getConflictReason())
                .notes(a.getNotes())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
