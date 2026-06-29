package com.healthtech.doctor.repository;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

public class DoctorSpecifications {

    public static Specification<Doctor> hasSpecialty(String specialtyName) {
        return (root, query, criteriaBuilder) -> {
            if (specialtyName == null || specialtyName.isBlank()) {
                return null; // no filter, contributes nothing
            }

            if(query != null) {
                query.distinct(true);
            }

            Join<Doctor, Specialty> specialtyJoin = root.join("specialties");
            return criteriaBuilder.equal(specialtyJoin.get("name"), specialtyName);
        };
    }

    public static Specification<Doctor> hasLanguage(Language language) {
        return (root, query, criteriaBuilder) -> {
            if (language == null) {
                return null;
            }

            if(query != null) {
                query.distinct(true);
            }
            Join<Doctor, Language> languageJoin = root.join("languages");
            return criteriaBuilder.equal(languageJoin, language);
        };
    }
}
