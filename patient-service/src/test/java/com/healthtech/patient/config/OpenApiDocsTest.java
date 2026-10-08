package com.healthtech.patient.config;

import com.healthtech.patient.security.SecurityConfig;
import com.healthtech.patient.service.AuthService;
import com.healthtech.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
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
    private AuthService authService;

    @MockitoBean
    private PatientService patientService;

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

    @Test
    void apiDocs_loginIsPublicAndOnlyItsDeclaredAuthErrorCarriesProblemBody() throws Exception {
        String login = "$.paths['/api/auth/login'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(login + ".security").isEmpty())
                .andExpect(jsonPath(login + ".responses['400']").exists())
                .andExpect(jsonPath(login + ".responses['401'].content['application/problem+json'].schema['$ref']")
                        .value(PROBLEM_REF))
                // public operation: no default 403
                .andExpect(jsonPath(login + ".responses['403']").doesNotExist());
    }

    @Test
    void apiDocs_registerDocumentsCreatedAndConflictWithProblemBody() throws Exception {
        String register = "$.paths['/api/auth/register'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(register + ".responses['201'].content['application/json']").exists())
                .andExpect(jsonPath(register + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(register + ".responses['409'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF));
    }

    @Test
    void apiDocs_securedOperationGetsDefaultAuthErrors_withProblemBodyOnlyWhereTheHandlerProducesOne() throws Exception {
        String profile = "$.paths['/api/patients/{id}'].get";
        mockMvc.perform(get("/v3/api-docs"))
                // secured operations inherit the global bearer requirement instead of repeating it
                .andExpect(jsonPath("$.security[0].bearer-jwt").exists())
                .andExpect(jsonPath(profile + ".security").doesNotExist())
                // the injected @AuthenticationPrincipal Jwt must not leak into the spec as a query parameter
                .andExpect(jsonPath(profile + ".parameters[?(@.name == 'jwt')]").isEmpty())
                .andExpect(jsonPath(profile + ".responses['400']").exists())
                // Spring Security's own 401 has an empty body, so the default carries no content
                .andExpect(jsonPath(profile + ".responses['401'].content").doesNotExist())
                // the ownership 403 is produced by the exception handler, so it is declared with a ProblemDetail
                .andExpect(jsonPath(profile + ".responses['403'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(profile + ".responses['404'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF));
    }
}
