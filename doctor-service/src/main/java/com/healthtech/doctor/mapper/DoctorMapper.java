package com.healthtech.doctor.mapper;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.dto.CreateDoctorRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.DoctorSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DoctorMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "registeredAt", ignore = true)
    @Mapping(target = "specialties", ignore = true)
    Doctor toEntity(CreateDoctorRequest request);

    DoctorResponse toDoctorResponse(Doctor doctor);

    @Mapping(target = "postalCode", source = "address.postalCode")
    @Mapping(target = "city", source = "address.city")
    DoctorSummaryResponse toDoctorSummaryResponse(Doctor doctor);
}
