package com.healthtech.patient.service;

import com.healthtech.patient.domain.Patient;
import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.exception.PatientNotFoundException;
import com.healthtech.patient.mapper.PatientMapper;
import com.healthtech.patient.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {
    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;

    public PatientResponse getPatientProfileById(UUID id){
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
        return patientMapper.toPatientResponse(patient);    }
}
