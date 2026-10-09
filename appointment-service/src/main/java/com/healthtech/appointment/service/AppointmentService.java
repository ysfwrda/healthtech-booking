package com.healthtech.appointment.service;

import com.healthtech.appointment.config.ChangePolicyProperties;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentChanged;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.event.DomainEventPublisher;
import com.healthtech.appointment.exception.*;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.*;
import com.healthtech.appointment.repository.AppointmentRepository;
import com.healthtech.appointment.service.booking.BookingRule;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
        appointment.setStatus(AppointmentStatus.CONFIRMED);

        ValidPatient patient = validPatientRepository.findById(appointment.getPatientId())
                .orElseThrow(() -> new PatientNotFoundException(appointment.getPatientId()));

        ValidDoctor doctor = validDoctorRepository.findById(appointment.getDoctorId())
                .orElseThrow(() -> new DoctorNotFoundException(appointment.getDoctorId()));

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
        log.info("Appointment booked, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    @Transactional
    public AppointmentResponse cancelAppointment(UUID appointmentId, UUID patientId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        if(!appointment.getPatientId().equals(patientId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
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
                .build();

        eventPublisher.publish("appointment.cancelled", saved.getId(), event.getEventId(), event);
        log.info("Appointment cancelled, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    @Transactional
    public AppointmentResponse updateAppointment(UUID appointmentId, AppointmentUpdateRequest request, UUID patientId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        if (!appointment.getPatientId().equals(patientId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new AppointmentNotChangeableException(appointmentId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int minNoticeHours = changePolicy.minNoticeHours();
        if (appointment.getDateTime().isBefore(now.plusHours(minNoticeHours))) {
            throw new ChangeWindowClosedException(minNoticeHours);
        }

        AppointmentType previousType = appointment.getType();
        LocalDateTime previousDateTime = appointment.getDateTime();

        if (request.getDateTime() != null && !request.getDateTime().equals(previousDateTime)) {
            ValidDoctor doctor = validDoctorRepository.findById(appointment.getDoctorId())
                    .orElseThrow(() -> new DoctorNotFoundException(appointment.getDoctorId()));
            bookingRules.forEach(rule -> rule.check(request.getDateTime(), doctor));
            appointment.setDateTime(request.getDateTime());
        }
        if (request.getType() != null) {
            appointment.setType(request.getType());
        }
        if (request.getNotes() != null) {
            appointment.setNotes(request.getNotes().isEmpty() ? null : request.getNotes());
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
                    .changedAt(now)
                    .build();
            eventPublisher.publish("appointment.changed", saved.getId(), event.getEventId(), event);
        }
        log.info("Appointment changed, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    public List<AppointmentResponse> getAppointmentsForPatient(UUID patientId) {
        return appointmentRepository.findByPatientIdOrderByDateTimeAsc(patientId).stream()
                .map(appointmentMapper::toResponse)
                .toList();
    }
}
