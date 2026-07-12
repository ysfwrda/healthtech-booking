package com.healthtech.doctor.config;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

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
    @Mock private KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @InjectMocks
    private DoctorSeeder doctorSeeder;

    @Test
    void run_allDoctorsMissing_savesSixAndPublishesEventForEach() {
        // Arrange
        when(doctorRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(specialtyRepository.findByName(anyString()))
                .thenAnswer(inv -> Optional.of(Specialty.builder().name(inv.getArgument(0)).build()));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");
        when(doctorRepository.saveAndFlush(any(Doctor.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act
        doctorSeeder.run();

        // Assert: one save and one published event per demo doctor
        verify(doctorRepository, times(6)).saveAndFlush(any(Doctor.class));
        verify(kafkaTemplate, times(6)).send(eq("doctor.registered"), any(DoctorRegistered.class));
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
        verify(kafkaTemplate, never()).send(anyString(), any());
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
