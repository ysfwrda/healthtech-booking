package com.healthtech.doctor.service;

import com.healthtech.doctor.dto.SpecialtyDto;
import com.healthtech.doctor.mapper.SpecialtyMapper;
import com.healthtech.doctor.repository.SpecialtyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SpecialtyService {

    private final SpecialtyRepository specialtyRepository;
    private final SpecialtyMapper specialtyMapper; // or reuse DoctorMapper if it has the specialty mapping

    public List<SpecialtyDto> getAllSpecialties() {
        return specialtyRepository.findAll().stream()
                .map(specialtyMapper::toDto)
                .toList();
    }
}
