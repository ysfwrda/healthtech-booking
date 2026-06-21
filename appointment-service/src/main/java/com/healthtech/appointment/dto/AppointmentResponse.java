package com.healthtech.appointment.dto;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentResponse {
    private UUID id;

    private UUID patientId;

    private UUID doctorId;

    private LocalDateTime dateTime;

    private Integer duration;

    private AppointmentType type;

    private AppointmentStatus status;

    private String notes;

    private LocalDateTime createdAt;
}
