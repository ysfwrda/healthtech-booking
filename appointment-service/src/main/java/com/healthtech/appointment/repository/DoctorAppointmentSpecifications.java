package com.healthtech.appointment.repository;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// Filters of the doctor's appointment list. A null filter is not applied; from and to are whole days, both inclusive.
public final class DoctorAppointmentSpecifications {

    private DoctorAppointmentSpecifications() {
    }

    public static Specification<Appointment> forDoctor(UUID doctorId, LocalDate from, LocalDate to,
                                                       Collection<AppointmentStatus> statuses) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("doctorId"), doctorId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dateTime"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("dateTime"), to.plusDays(1).atStartOfDay()));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
