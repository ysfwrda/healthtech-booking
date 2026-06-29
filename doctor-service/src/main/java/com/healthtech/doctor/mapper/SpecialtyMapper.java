package com.healthtech.doctor.mapper;

import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.SpecialtyDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface SpecialtyMapper {
    SpecialtyDto toDto(Specialty specialty);
}
