package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.event.AppointmentNotificationEvent;
import com.healthtech.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;

    public void record(AppointmentNotificationEvent event) {
        Notification notification = Notification.builder()
                .appointmentId(event.getAppointmentId())
                .patientId(event.getPatientId())
                .doctorId(event.getDoctorId())
                .type(event.notificationType())
                .message(event.notificationMessage())
                .build();

        notificationRepository.save(notification);
    }
}
