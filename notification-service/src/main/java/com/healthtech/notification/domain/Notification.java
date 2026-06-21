package com.healthtech.notification.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private String message;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
