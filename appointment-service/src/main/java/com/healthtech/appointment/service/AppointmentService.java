package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.exception.*;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final int SLOT_DURATION_MINUTES = 30;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final ValidPatientRepository validPatientRepository;
    private final ValidDoctorRepository validDoctorRepository;
    private final KafkaTemplate <String, AppointmentBooked> bookedEventKafkaTemplate;
    private final KafkaTemplate<String, AppointmentCancelled> cancelledEventKafkaTemplate;

    public AppointmentResponse bookAppointment(AppointmentRequest request) {
        Appointment appointment = appointmentMapper.toEntity(request);
        appointment.setDuration(SLOT_DURATION_MINUTES);
        appointment.setStatus(AppointmentStatus.CONFIRMED);

        ValidPatient patient = validPatientRepository.findById(appointment.getPatientId())
                .orElseThrow(() -> new UnknownPatientException(appointment.getPatientId())); // TODO: Status 404

        ValidDoctor doctor = validDoctorRepository.findById(appointment.getDoctorId())
                .orElseThrow(() -> new UnknownDoctorException(appointment.getDoctorId())); // TODO: Status 404

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
            throw new SlotNotAlignedException(); // TODO: Status 400
        }

        if(!appointmentWithinOpeningHours){
            throw new OutsideOpeningHoursException(); //TODO: Status 422
        }

        Appointment saved;
        try {
            saved = appointmentRepository.save(appointment);
        }
        catch (DataIntegrityViolationException e) {
            throw new SlotAlreadyBookedException(); // TOOD: Status 409
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

        bookedEventKafkaTemplate.send("appointment.booked", event);
        return appointmentMapper.toResponse(saved);
    }

    public AppointmentResponse cancelAppointment(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found: " + appointmentId));
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

        cancelledEventKafkaTemplate.send("appointment.cancelled", event);
        return appointmentMapper.toResponse(saved);
    }
}
