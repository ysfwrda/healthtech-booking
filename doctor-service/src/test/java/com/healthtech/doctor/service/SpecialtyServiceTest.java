package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.SpecialtyDto;
import com.healthtech.doctor.mapper.SpecialtyMapper;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecialtyServiceTest {

    @Mock private SpecialtyRepository specialtyRepository;
    @Mock private SpecialtyMapper specialtyMapper;

    @InjectMocks
    private SpecialtyService specialtyService;

    @Test
    void getAllSpecialties_withSeededSpecialties_returnsMappedDtos() {
        // Arrange
        Specialty cardiology = Specialty.builder().id(UUID.randomUUID()).name("Cardiology").build();
        Specialty neurology = Specialty.builder().id(UUID.randomUUID()).name("Neurology").build();
        SpecialtyDto cardiologyDto = new SpecialtyDto(cardiology.getId(), "Cardiology");
        SpecialtyDto neurologyDto = new SpecialtyDto(neurology.getId(), "Neurology");

        when(specialtyRepository.findAll()).thenReturn(List.of(cardiology, neurology));
        when(specialtyMapper.toDto(cardiology)).thenReturn(cardiologyDto);
        when(specialtyMapper.toDto(neurology)).thenReturn(neurologyDto);

        // Act
        List<SpecialtyDto> result = specialtyService.getAllSpecialties();

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).extracting(SpecialtyDto::getName)
                .containsExactlyInAnyOrder("Cardiology", "Neurology");
        verify(specialtyRepository).findAll();
    }

    @Test
    void getAllSpecialties_emptyRepository_returnsEmptyList() {
        // Arrange
        when(specialtyRepository.findAll()).thenReturn(List.of());

        // Act
        List<SpecialtyDto> result = specialtyService.getAllSpecialties();

        // Assert
        assertThat(result).isEmpty();
        verify(specialtyRepository).findAll();
    }
}
