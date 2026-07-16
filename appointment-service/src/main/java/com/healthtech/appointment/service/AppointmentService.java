package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.exception.*;
import com.healthtech.appointment.filter.CorrelationIdFilter;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.*;
import com.healthtech.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final int SLOT_DURATION_MINUTES = 30;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final ValidPatientRepository validPatientRepository;
    private final ValidDoctorRepository validDoctorRepository;
    private final KafkaTemplate <String, AppointmentBooked> bookedEventKafkaTemplate;
    private final KafkaTemplate<String, AppointmentCancelled> cancelledEventKafkaTemplate;

    public AppointmentResponse bookAppointment(AppointmentRequest request, UUID patientId) {
        Appointment appointment = appointmentMapper.toEntity(request);
        appointment.setPatientId(patientId);
        appointment.setDuration(SLOT_DURATION_MINUTES);
        appointment.setStatus(AppointmentStatus.CONFIRMED);

        ValidPatient patient = validPatientRepository.findById(appointment.getPatientId())
                .orElseThrow(() -> new PatientNotFoundException(appointment.getPatientId()));

        ValidDoctor doctor = validDoctorRepository.findById(appointment.getDoctorId())
                .orElseThrow(() -> new DoctorNotFoundException(appointment.getDoctorId()));

        LocalTime slotStart = request.getDateTime().toLocalTime();

        boolean aligned = (slotStart.getMinute() == 0 || slotStart.getMinute() == 30)
                && slotStart.getSecond() == 0
                && slotStart.getNano() == 0;

        LocalTime slotEnd = slotStart.plusMinutes(SLOT_DURATION_MINUTES);
        DayOfWeek day = request.getDateTime().getDayOfWeek();

        boolean appointmentWithinOpeningHours = doctor.getOpeningHours()
                .stream()
                .filter( openingHours -> openingHours.getDayOfWeek() == day)
                .anyMatch(oh -> !oh.getStartTime().isAfter(slotStart)   // start <= slotStart
                        && !oh.getEndTime().isBefore(slotEnd));      // end   >= slotEnd

        if(!aligned ) {
            throw new SlotNotAlignedException();
        }

        if(!appointmentWithinOpeningHours){
            throw new OutsideOpeningHoursException();
        }

        Appointment saved;
        try {
            saved = appointmentRepository.save(appointment);
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

        ProducerRecord<String, AppointmentBooked> bookedRecord =
                new ProducerRecord<>("appointment.booked", event);
        bookedRecord.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                correlationIdOrGenerate().getBytes(StandardCharsets.UTF_8));
        bookedEventKafkaTemplate.send(bookedRecord);
        log.info("Appointment booked, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    public AppointmentResponse cancelAppointment(UUID appointmentId, UUID patientId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        if(!appointment.getPatientId().equals(patientId)) {
            throw new AppointmentAccessDeniedException(appointmentId);
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
                .cancelledAt(LocalDateTime.now())
                .build();

        ProducerRecord<String, AppointmentCancelled> cancelledRecord =
                new ProducerRecord<>("appointment.cancelled", event);
        cancelledRecord.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                correlationIdOrGenerate().getBytes(StandardCharsets.UTF_8));
        cancelledEventKafkaTemplate.send(cancelledRecord);
        log.info("Appointment cancelled, appointmentId {}", saved.getId());
        return appointmentMapper.toResponse(saved);
    }

    public List<AppointmentResponse> getAppointmentsForPatient(UUID patientId) {
        return appointmentRepository.findByPatientIdOrderByDateTimeAsc(patientId).stream()
                .map(appointmentMapper::toResponse)
                .toList();
    }

    public AvailableSlotsResponse getAvailableSlots(UUID doctorId, LocalDate date) {
        ValidDoctor doctor = validDoctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException(doctorId));

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

        List<Appointment> takenAppointments = appointmentRepository
                .findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
                        doctorId, startOfDay, endOfDay, AppointmentStatus.CANCELLED);

        List<OpeningHours> openingHoursForDay = doctor.getOpeningHours()
                .stream()
                .filter(openingHours -> openingHours.getDayOfWeek() == date.getDayOfWeek())
                .toList();

        List<LocalDateTime> availableSlots = new ArrayList<>();
        for (OpeningHours openingHours : openingHoursForDay) {
            LocalDateTime currentSlot = date.atTime(openingHours.getStartTime());
            LocalDateTime lastSlot = date.atTime(openingHours.getEndTime().minusMinutes(30L));
            while(!currentSlot.isAfter(lastSlot)) {
                availableSlots.add(currentSlot);
                currentSlot = currentSlot.plusMinutes(30);
            }
        }
        Set<LocalDateTime> takenAppointmentSlots = takenAppointments.stream()
                .map(Appointment::getDateTime)
                .collect(Collectors.toSet());

        availableSlots.removeAll(takenAppointmentSlots);

        if(date.isEqual(LocalDate.now())) {
            availableSlots.removeIf(slot -> slot.isBefore(LocalDateTime.now()));
        }

        return AvailableSlotsResponse.builder()
                .doctorId(doctorId)
                .date(date)
                .availableSlots(availableSlots)
                .build();
    }

    // Threads the current request's correlation id onto outgoing Kafka messages so a
    // consumer (e.g. Notification Service) can tie its own log lines back to the request
    // that produced the event. Falls back to a fresh id outside a request context.
    private String correlationIdOrGenerate() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }
}
