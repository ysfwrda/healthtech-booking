package com.healthtech.doctor.config;

import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpecialtySeederTest {

    @Mock private SpecialtyRepository specialtyRepository;

    @InjectMocks
    private SpecialtySeeder specialtySeeder;

    @Test
    void run_allSpecialtiesMissing_savesAllSevenEntries() {
        // Arrange: repository has nothing
        when(specialtyRepository.findByName(anyString())).thenReturn(Optional.empty());

        // Act
        specialtySeeder.run();

        // Assert: one save per specialty name
        verify(specialtyRepository, times(7)).save(any(Specialty.class));
    }

    @Test
    void run_allSpecialtiesAlreadyExist_savesNothing() {
        // Arrange: every name lookup returns an existing entity
        when(specialtyRepository.findByName(anyString()))
                .thenReturn(Optional.of(Specialty.builder().name("any").build()));

        // Act
        specialtySeeder.run();

        // Assert: idempotent; nothing saved
        verify(specialtyRepository, never()).save(any());
    }

    @Test
    void run_someSpecialtiesMissing_savesOnlyMissingOnes() {
        // Arrange: "Cardiology" is present, everything else is absent
        when(specialtyRepository.findByName("Cardiology"))
                .thenReturn(Optional.of(Specialty.builder().name("Cardiology").build()));
        when(specialtyRepository.findByName(argThat(name -> !"Cardiology".equals(name))))
                .thenReturn(Optional.empty());

        // Act
        specialtySeeder.run();

        // Assert: 6 saves (7 total minus the 1 that already exists)
        ArgumentCaptor<Specialty> captor = ArgumentCaptor.forClass(Specialty.class);
        verify(specialtyRepository, times(6)).save(captor.capture());
        List<String> savedNames = captor.getAllValues().stream().map(Specialty::getName).toList();
        assertThat(savedNames).doesNotContain("Cardiology");
    }
}
