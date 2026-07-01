package com.healthtech.appointment.readmodel;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "valid_doctor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidDoctor {
    @Id
    @Column(name = "doctor_id")
    private UUID doctorId;

    private String firstName;
    private String lastName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "valid_doctor_opening_hours", joinColumns = @JoinColumn(name = "doctor_id"))
    @Builder.Default
    private Set<OpeningHours> openingHours = new HashSet<>();
}
