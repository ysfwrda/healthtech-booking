package com.healthtech.doctor.config;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.event.DomainEventPublisher;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorSeederTest {

    @Mock private DoctorRepository doctorRepository;
    @Mock private SpecialtyRepository specialtyRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DomainEventPublisher eventPublisher;

    private DoctorSeeder doctorSeeder;

    @BeforeEach
    void setUp() {
        doctorSeeder = new DoctorSeeder(doctorRepository, specialtyRepository, passwordEncoder,
                eventPublisher, TransactionOperations.withoutTransaction());
    }

    private void stubAllDoctorsMissing() {
        when(doctorRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(specialtyRepository.findByName(anyString()))
                .thenAnswer(inv -> Optional.of(Specialty.builder().name(inv.getArgument(0)).build()));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");
    }

    @Test
    void run_allDoctorsMissing_savesSixAndPublishesEventForEach() {
        // Arrange
        stubAllDoctorsMissing();
        when(doctorRepository.saveAndFlush(any(Doctor.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act
        doctorSeeder.run();

        // Assert: one save and one published event per demo doctor
        verify(doctorRepository, times(6)).saveAndFlush(any(Doctor.class));
        verify(eventPublisher, times(6)).publish(eq("doctor.registered"), any(), any(), any(DoctorRegistered.class));
    }

    @Test
    void run_doctorMissing_publishesEventKeyedBySavedDoctorIdAndOwnEventId() {
        // Arrange
        stubAllDoctorsMissing();
        when(doctorRepository.saveAndFlush(any(Doctor.class))).thenAnswer(inv -> {
            Doctor doctor = inv.getArgument(0);
            doctor.setId(UUID.randomUUID());
            return doctor;
        });

        // Act
        doctorSeeder.run();

        // Assert
        ArgumentCaptor<UUID> aggregateIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> eventIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<DoctorRegistered> events = ArgumentCaptor.forClass(DoctorRegistered.class);
        verify(eventPublisher, times(6)).publish(
                eq("doctor.registered"), aggregateIds.capture(), eventIds.capture(), events.capture());
        for (int i = 0; i < 6; i++) {
            DoctorRegistered event = events.getAllValues().get(i);
            assertThat(aggregateIds.getAllValues().get(i)).isNotNull().isEqualTo(event.getDoctorId());
            assertThat(eventIds.getAllValues().get(i)).isNotNull().isEqualTo(event.getEventId());
        }
    }

    @Test
    void run_publishFails_propagatesException() {
        // Arrange
        stubAllDoctorsMissing();
        when(doctorRepository.saveAndFlush(any(Doctor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("outbox write failed"))
                .when(eventPublisher).publish(anyString(), any(), any(), any());

        // Act & Assert
        assertThatThrownBy(() -> doctorSeeder.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox write failed");
    }

    @Test
    void run_allDoctorsAlreadyExist_savesNothingAndPublishesNothing() {
        // Arrange
        when(doctorRepository.findByEmail(anyString()))
                .thenReturn(Optional.of(Doctor.builder().build()));

        // Act
        doctorSeeder.run();

        // Assert: idempotent
        verify(doctorRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void run_specialtyMissing_throwsIllegalStateExceptionNamingTheMissingSpecialty() {
        // Arrange: SpecialtySeeder hasn't run (or a name drifted out of sync)
        when(doctorRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(specialtyRepository.findByName(anyString())).thenReturn(Optional.empty());

        // Act & Assert: fails loudly rather than silently skipping or NPE-ing
        assertThatThrownBy(() -> doctorSeeder.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("General Practice");

        verify(doctorRepository, never()).saveAndFlush(any());
    }

    @Test
    void run_passwordHashedViaRealEncoder_notStoredAsPlaintext() {
        // Arrange
        when(doctorRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(specialtyRepository.findByName(anyString()))
                .thenAnswer(inv -> Optional.of(Specialty.builder().name(inv.getArgument(0)).build()));
        when(passwordEncoder.encode(DoctorSeeder.DEMO_PASSWORD)).thenReturn("$2a$hashed");
        when(doctorRepository.saveAndFlush(any(Doctor.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act
        doctorSeeder.run();

        // Assert
        ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository, times(6)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).allMatch(d -> "$2a$hashed".equals(d.getPasswordHash()));
    }
}
