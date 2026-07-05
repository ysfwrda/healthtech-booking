package com.healthtech.appointment.dto;

import com.healthtech.appointment.domain.AppointmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRequest {
    @NotNull
    private UUID doctorId;

    @NotNull
    private LocalDateTime dateTime;

    @NotNull
    private AppointmentType type;

    @Size(max = 500)
    private String notes;
}
