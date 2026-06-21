package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.domain.NotificationType;
import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;

    public void createForBookedAppointment(AppointmentBooked event) {
        Notification notification = Notification.builder()
                .appointmentId(event.getAppointmentId())
                .patientId(event.getPatientId())
                .doctorId(event.getDoctorId())
                .type(NotificationType.APPOINTMENT_BOOKED)
                .message("Appointment booked for " + event.getDateTime())
                .build();

        notificationRepository.save(notification);
    }

    public void createForCancelledAppointment(AppointmentCancelled event) {
        Notification notification = Notification.builder()
                .appointmentId(event.getAppointmentId())
                .patientId(event.getPatientId())
                .doctorId(event.getDoctorId())
                .type(NotificationType.APPOINTMENT_CANCELLED)
                .message("Appointment cancelled for " + event.getDateTime())
                .build();

        notificationRepository.save(notification);
    }
}
