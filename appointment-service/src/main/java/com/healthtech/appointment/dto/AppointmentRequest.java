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
    // Provisional: will be derived from the JWT subject in the gateway auth work.
    // Do not treat body-supplied patientId as trusted long-term.
    @NotNull
    private UUID patientId;

    @NotNull
    private UUID doctorId;

    @NotNull
    private LocalDateTime dateTime;

    @NotNull
    private AppointmentType type;

    @Size(max = 500)
    private String notes;
}
