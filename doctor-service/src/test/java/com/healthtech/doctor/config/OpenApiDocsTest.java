package com.healthtech.doctor.config;

import com.healthtech.doctor.security.SecurityConfig;
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

    // Every doctor operation is currently public (the API description says so). If one becomes secured, one of
    // these fails, which is the cue to update the description and add the 401/403 expectations.
    @Test
    void apiDocs_everyOperationOptsOutOfSecurityAndGetsNoDefaultAuthErrors() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths.*.*[?(@.security.length() == 0)]").isNotEmpty())
                .andExpect(jsonPath("$.paths.*.*[?(!@.security || @.security.length() > 0)]").isEmpty())
                .andExpect(jsonPath("$.paths.*.*.responses['403']").isEmpty());
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
