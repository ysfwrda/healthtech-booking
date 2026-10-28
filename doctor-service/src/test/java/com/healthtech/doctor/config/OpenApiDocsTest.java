package com.healthtech.doctor.config;

import com.healthtech.doctor.security.SecurityConfig;
import com.healthtech.doctor.service.AvailabilityService;
import com.healthtech.doctor.service.DoctorAuthService;
import com.healthtech.doctor.service.DoctorService;
import com.healthtech.doctor.service.SpecialtyService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pins the generated /v3/api-docs, because OpenApiErrorCustomizer derives responses from the shape of each
// operation (and relies on springdoc internals), so a springdoc upgrade or a new controller can change the spec
// without any compile error. SecurityConfig is imported so the test also proves the docs endpoint is public.
@WebMvcTest
@Import({SecurityConfig.class, OpenApiConfig.class, OpenApiErrorCustomizer.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class,
        SpringDocConfigProperties.class})
class OpenApiDocsTest {
    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DoctorAuthService doctorAuthService;

    @MockitoBean
    private DoctorService doctorService;

    @MockitoBean
    private AvailabilityService availabilityService;

    @MockitoBean
    private SpecialtyService specialtyService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void apiDocs_definesBearerSchemeAndSharedProblemDetailSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.status").exists())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.errors").exists());
    }

    // Only the availability operations under /api/doctors/me are secured. If another one becomes secured, this fails,
    // which is the cue to update the API description and the expectations.
    @Test
    void apiDocs_publicOperationsOptOutOfSecurityAndGetNoDefaultAuthErrors() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/doctors/login'].post.security.length()").value(0))
                .andExpect(jsonPath("$.paths['/api/doctors/register'].post.security.length()").value(0))
                .andExpect(jsonPath("$.paths['/api/doctors/{id}'].get.security.length()").value(0))
                .andExpect(jsonPath("$.paths['/api/specialties'].get.security.length()").value(0))
                .andExpect(jsonPath("$.paths['/api/doctors/login'].post.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/doctors/register'].post.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/doctors/{id}'].get.responses['403']").doesNotExist());
    }

    @Test
    void apiDocs_availabilityOperationsAreSecuredAndDocument401And403() throws Exception {
        // Act & Assert
        for (String op : new String[]{
                "$.paths['/api/doctors/me/availability'].get",
                "$.paths['/api/doctors/me/availability/opening-hours'].put",
                "$.paths['/api/doctors/me/availability/vacations'].post",
                "$.paths['/api/doctors/me/availability/vacations/{vacationId}'].delete"}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath(op + ".security").doesNotExist())
                    .andExpect(jsonPath(op + ".responses['401']").exists())
                    .andExpect(jsonPath(op + ".responses['403']").exists())
                    .andExpect(jsonPath(op + ".responses['404'].content['application/problem+json']"
                            + ".schema['$ref']").value(PROBLEM_REF));
        }
    }

    @Test
    void apiDocs_availabilityWritesDocumentSuccessAndProblemBodiesForValidation() throws Exception {
        // Arrange
        String hours = "$.paths['/api/doctors/me/availability/opening-hours'].put";
        String vacations = "$.paths['/api/doctors/me/availability/vacations'].post";
        String delete = "$.paths['/api/doctors/me/availability/vacations/{vacationId}'].delete";

        // Act & Assert
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(hours + ".responses['200']").exists())
                .andExpect(jsonPath(hours + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(vacations + ".responses['201']").exists())
                .andExpect(jsonPath(vacations + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(delete + ".responses['204']").exists());
    }

    @Test
    void apiDocs_loginDeclares401WithProblemBodyAndDefault400() throws Exception {
        String login = "$.paths['/api/doctors/login'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(login + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(login + ".responses['401'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF));
    }

    @Test
    void apiDocs_registerDocumentsCreatedAndEveryDeclaredErrorWithProblemBody() throws Exception {
        String register = "$.paths['/api/doctors/register'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(register + ".responses['201'].content['application/json']").exists())
                .andExpect(jsonPath(register + ".responses['404'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(register + ".responses['409'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF));
    }

    @Test
    void apiDocs_parameterlessListGetsNoDefault400() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/specialties'].get.responses['400']").doesNotExist());
    }
}
