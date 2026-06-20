package com.healthtech.appointment.service;

import com.healthtech.appointment.event.AppointmentBookedEvent;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentCancelledEvent;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final KafkaTemplate <String, AppointmentBookedEvent> bookedEventKafkaTemplate;
    private final KafkaTemplate<String, AppointmentCancelledEvent> cancelledEventKafkaTemplate;

    public AppointmentResponse bookAppointment(AppointmentRequest request) {
        Appointment appointment = appointmentMapper.toEntity(request);
        appointment.setStatus(AppointmentStatus.PENDING);
        Appointment saved = appointmentRepository.save(appointment);
        AppointmentBookedEvent event = AppointmentBookedEvent.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(saved.getId())
                .patientId(saved.getPatientId())
                .doctorId(saved.getDoctorId())
                .duration(saved.getDuration())
                .dateTime(saved.getDateTime())
                .bookedAt(LocalDateTime.now())
                .build();

        bookedEventKafkaTemplate.send("appointment.booked", event);
        return appointmentMapper.toResponse(saved);
    }

    public AppointmentResponse cancelAppointment(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found: " + appointmentId));
        appointment.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        AppointmentCancelledEvent event = AppointmentCancelledEvent.builder()
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