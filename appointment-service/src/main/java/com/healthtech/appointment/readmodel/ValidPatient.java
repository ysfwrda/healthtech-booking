package com.healthtech.appointment.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "valid_patient")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidPatient {
    @Id
    @Column(name = "patient_id")
    private UUID patientId;

    private String email;
    private String firstName;
    private String lastName;
}
