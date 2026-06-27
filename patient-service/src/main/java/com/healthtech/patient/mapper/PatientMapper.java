package com.healthtech.patient.mapper;

import com.healthtech.patient.domain.Patient;
import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.dto.RegisterRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PatientMapper {
    @Mapping(target = "passwordHash", ignore = true)
    Patient toEntity(RegisterRequest registerRequest);
    PatientResponse toPatientResponse(Patient patient);
}
