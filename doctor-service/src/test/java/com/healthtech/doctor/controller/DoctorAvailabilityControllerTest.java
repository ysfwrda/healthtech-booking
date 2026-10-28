package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.AvailabilityResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.dto.VacationDto;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.exception.InvalidVacationException;
import com.healthtech.doctor.exception.VacationNotFoundException;
import com.healthtech.doctor.security.SecurityConfig;
import com.healthtech.doctor.service.AvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Slice test with the real SecurityConfig: /api/doctors/me/** must be DOCTOR-only even though
// GET /api/doctors/** is public.
@WebMvcTest(DoctorAvailabilityController.class)
@Import(SecurityConfig.class)
class DoctorAvailabilityControllerTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();
    private static final String BASE = "/api/doctors/me/availability";
    private static final String VALID_HOURS = """
            {"openingHours":[{"dayOfWeek":"MONDAY","startTime":"09:00","endTime":"12:00"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityService availabilityService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID doctorId = UUID.randomUUID();

    private RequestPostProcessor asDoctor() {
        return jwt().jwt(b -> b.subject(doctorId.toString()).claim("role", "DOCTOR")).authorities(ROLE_AUTHORITIES);
    }

    private RequestPostProcessor asPatient() {
        return jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("role", "PATIENT"))
                .authorities(ROLE_AUTHORITIES);
    }

    private AvailabilityResponse sampleAvailability() {
        return AvailabilityResponse.builder()
                .openingHours(Set.of(OpeningHoursDto.builder().dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).build()))
                .vacations(List.of(VacationDto.builder().id(UUID.randomUUID())
                        .startDate(LocalDate.of(2026, 12, 24)).endDate(LocalDate.of(2026, 12, 31)).build()))
                .build();
    }

    // -- security ----------------------------------------------------------------

    @Test
    void getAvailability_noToken_returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(availabilityService);
    }

    @Test
    void getAvailability_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE).with(asPatient()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(availabilityService);
    }

    @Test
    void replaceOpeningHours_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asPatient())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_HOURS))
                .andExpect(status().isForbidden());
        verifyNoInteractions(availabilityService);
    }

    @Test
    void addVacation_noToken_returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(post(BASE + "/vacations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-12-24\",\"endDate\":\"2026-12-31\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeVacation_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(delete(BASE + "/vacations/{id}", UUID.randomUUID()).with(asPatient()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(availabilityService);
    }

    // -- GET ---------------------------------------------------------------------

    @Test
    void getAvailability_doctorToken_usesTokenSubjectAndReturns200() throws Exception {
        // Arrange
        when(availabilityService.getAvailability(doctorId)).thenReturn(sampleAvailability());

        // Act & Assert
        mockMvc.perform(get(BASE).with(asDoctor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.vacations[0].startDate").value("2026-12-24"))
                .andExpect(jsonPath("$.vacations[0].id").exists());
    }

    @Test
    void getAvailability_unknownDoctor_returns404ProblemDetail() throws Exception {
        // Arrange
        when(availabilityService.getAvailability(doctorId)).thenThrow(new DoctorNotFoundException(doctorId));

        // Act & Assert
        mockMvc.perform(get(BASE).with(asDoctor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Doctor Not Found"));
    }

    // -- PUT opening-hours -------------------------------------------------------

    @Test
    void replaceOpeningHours_validBody_returns200WithAvailability() throws Exception {
        // Arrange
        when(availabilityService.replaceOpeningHours(eq(doctorId), any())).thenReturn(sampleAvailability());

        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_HOURS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingHours[0].startTime").value("09:00:00"));
    }

    @Test
    void replaceOpeningHours_emptySet_isAccepted() throws Exception {
        // Arrange
        when(availabilityService.replaceOpeningHours(eq(doctorId), any()))
                .thenReturn(AvailabilityResponse.builder().openingHours(Set.of()).vacations(List.of()).build());

        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingHours\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void replaceOpeningHours_missingField_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
        verify(availabilityService, never()).replaceOpeningHours(any(), any());
    }

    @Test
    void replaceOpeningHours_offGridBlock_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingHours\":[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:15\",\"endTime\":\"12:00\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replaceOpeningHours_overlappingBlocks_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingHours\":[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00\",\"endTime\":\"12:00\"},"
                                + "{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"11:30\",\"endTime\":\"15:00\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replaceOpeningHours_adjacentBlocks_areAccepted() throws Exception {
        // Arrange
        when(availabilityService.replaceOpeningHours(eq(doctorId), any())).thenReturn(sampleAvailability());

        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingHours\":[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00\",\"endTime\":\"12:00\"},"
                                + "{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"12:00\",\"endTime\":\"15:00\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void replaceOpeningHours_endNotAfterStart_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingHours\":[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00\",\"endTime\":\"09:00\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replaceOpeningHours_unknownDoctor_returns404() throws Exception {
        // Arrange
        when(availabilityService.replaceOpeningHours(eq(doctorId), any())).thenThrow(new DoctorNotFoundException(doctorId));

        // Act & Assert
        mockMvc.perform(put(BASE + "/opening-hours").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_HOURS))
                .andExpect(status().isNotFound());
    }

    // -- POST vacations ----------------------------------------------------------

    @Test
    void addVacation_validBody_returns201WithVacation() throws Exception {
        // Arrange
        UUID vacationId = UUID.randomUUID();
        when(availabilityService.addVacation(eq(doctorId), any())).thenReturn(VacationDto.builder().id(vacationId)
                .startDate(LocalDate.of(2026, 12, 24)).endDate(LocalDate.of(2026, 12, 31)).build());

        // Act & Assert
        mockMvc.perform(post(BASE + "/vacations").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-12-24\",\"endDate\":\"2026-12-31\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(vacationId.toString()))
                .andExpect(jsonPath("$.endDate").value("2026-12-31"));
    }

    @Test
    void addVacation_missingEndDate_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(post(BASE + "/vacations").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startDate\":\"2026-12-24\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.endDate").exists());
    }

    @Test
    void addVacation_invalidPeriod_returns400ProblemDetail() throws Exception {
        // Arrange
        when(availabilityService.addVacation(eq(doctorId), any()))
                .thenThrow(new InvalidVacationException("endDate must not be in the past"));

        // Act & Assert
        mockMvc.perform(post(BASE + "/vacations").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2020-01-01\",\"endDate\":\"2020-01-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Vacation"));
    }

    @Test
    void addVacation_malformedDate_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(post(BASE + "/vacations").with(asDoctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"not-a-date\",\"endDate\":\"2026-12-31\"}"))
                .andExpect(status().isBadRequest());
    }

    // -- DELETE vacations --------------------------------------------------------

    @Test
    void removeVacation_ownVacation_returns204() throws Exception {
        // Arrange
        UUID vacationId = UUID.randomUUID();

        // Act & Assert
        mockMvc.perform(delete(BASE + "/vacations/{id}", vacationId).with(asDoctor()))
                .andExpect(status().isNoContent());
        verify(availabilityService).removeVacation(doctorId, vacationId);
    }

    @Test
    void removeVacation_unknownVacation_returns404ProblemDetail() throws Exception {
        // Arrange
        UUID vacationId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new VacationNotFoundException(vacationId))
                .when(availabilityService).removeVacation(doctorId, vacationId);

        // Act & Assert
        mockMvc.perform(delete(BASE + "/vacations/{id}", vacationId).with(asDoctor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Vacation Not Found"));
    }
}
