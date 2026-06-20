package com.healthtech.appointment.mapper;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface AppointmentMapper {
    Appointment toEntity(AppointmentRequest appointmentRequest);
    AppointmentResponse toResponse(Appointment appointment);
}
