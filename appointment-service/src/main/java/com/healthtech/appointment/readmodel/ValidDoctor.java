package com.healthtech.appointment.readmodel;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "valid_doctor_vacation", joinColumns = @JoinColumn(name = "doctor_id"))
    @Builder.Default
    private Set<VacationPeriod> vacations = new HashSet<>();

    // True for the seeded demo doctors: their bookings are confirmed on the spot, others start PENDING.
    // The column definition gives rows that existed before this field a value.
    @Column(columnDefinition = "boolean not null default false")
    private boolean autoConfirmBookings;

    // Version of the last doctor.availability-updated applied; null while none has been.
    private Long availabilityVersion;

    public List<OpeningHours> openingHoursOn(DayOfWeek day) {
        return openingHours.stream()
                .filter(oh -> oh.getDayOfWeek() == day)
                .toList();
    }

    public boolean isOpenFor(DayOfWeek day, LocalTime start, LocalTime end) {
        return openingHoursOn(day).stream().anyMatch(oh -> oh.covers(start, end));
    }

    public boolean isOnVacation(LocalDate day) {
        return vacations.stream().anyMatch(v -> v.covers(day));
    }
}
