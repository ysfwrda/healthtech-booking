package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.DoctorSummaryResponse;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.mapper.DoctorMapper;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.DoctorSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final DoctorMapper doctorMapper;

    @Transactional(readOnly = true)
    public DoctorResponse getDoctorById(UUID id) {
        Doctor doctor = doctorRepository.findWithDetailsById(id)
                .orElseThrow(() -> new DoctorNotFoundException(id));
        return doctorMapper.toDoctorResponse(doctor);
    }

    @Transactional(readOnly = true)
    public List<DoctorSummaryResponse> findDoctors(String specialty, Language language) {
        Specification<Doctor> spec = Specification
                .allOf(DoctorSpecifications.hasSpecialty(specialty), DoctorSpecifications.hasLanguage(language));

        return doctorRepository.findAll(spec).stream()
                .map(doctorMapper::toDoctorSummaryResponse)
                .toList();
    }
}
