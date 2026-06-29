package com.healthtech.doctor.repository;

import com.healthtech.doctor.domain.Doctor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DoctorSpecificationsTest {

    @Mock private Root<Doctor> root;
    @Mock private CriteriaQuery<?> query;
    @Mock private CriteriaBuilder cb;

    // -- hasSpecialty ---------------------------------------------------------

    @Test
    void hasSpecialty_nullName_returnsNullPredicate() {
        // Arrange
        Specification<Doctor> spec = DoctorSpecifications.hasSpecialty(null);

        // Act
        Predicate result = spec.toPredicate(root, query, cb);

        // Assert: null signals "no restriction" to Specification.allOf
        assertThat(result).isNull();
        verifyNoInteractions(root, cb);
    }

    @Test
    void hasSpecialty_blankName_returnsNullPredicate() {
        // Arrange
        Specification<Doctor> spec = DoctorSpecifications.hasSpecialty("   ");

        // Act
        Predicate result = spec.toPredicate(root, query, cb);

        // Assert
        assertThat(result).isNull();
        verifyNoInteractions(root, cb);
    }

    // -- hasLanguage ----------------------------------------------------------

    @Test
    void hasLanguage_nullLanguage_returnsNullPredicate() {
        // Arrange
        Specification<Doctor> spec = DoctorSpecifications.hasLanguage(null);

        // Act
        Predicate result = spec.toPredicate(root, query, cb);

        // Assert
        assertThat(result).isNull();
        verifyNoInteractions(root, cb);
    }
}
