package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final ValidDoctorRepository validDoctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final SlotPolicy slotPolicy;
    private final Clock clock;

    public AvailableSlotsResponse getAvailableSlots(UUID doctorId, LocalDate date) {
        ValidDoctor doctor = validDoctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException(doctorId));

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

        List<Appointment> takenAppointments = appointmentRepository
                .findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
                        doctorId, startOfDay, endOfDay, AppointmentStatus.CANCELLED);

        List<LocalDateTime> availableSlots = new ArrayList<>();
        for (OpeningHours openingHours : doctor.openingHoursOn(date.getDayOfWeek())) {
            availableSlots.addAll(slotPolicy.slotsWithin(date, openingHours));
        }
        Set<LocalDateTime> takenAppointmentSlots = takenAppointments.stream()
                .map(Appointment::getDateTime)
                .collect(Collectors.toSet());

        availableSlots.removeAll(takenAppointmentSlots);

        if(date.isEqual(LocalDate.now(clock))) {
            availableSlots.removeIf(slot -> slot.isBefore(LocalDateTime.now(clock)));
        }

        return AvailableSlotsResponse.builder()
                .doctorId(doctorId)
                .date(date)
                .availableSlots(availableSlots)
                .build();
    }
}
