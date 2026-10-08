package com.healthtech.appointment.config;

import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import com.healthtech.appointment.service.AvailabilityService;
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
    private AppointmentService appointmentService;

    @MockitoBean
    private AvailabilityService availabilityService;

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
    void apiDocs_availabilityIsPublicWithDocumentedParametersAndNoAuthErrors() throws Exception {
        String availability = "$.paths['/api/availability'].get";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(availability + ".security").isEmpty())
                .andExpect(jsonPath(availability + ".parameters[?(@.name == 'doctorId')]").isNotEmpty())
                .andExpect(jsonPath(availability + ".parameters[?(@.name == 'date')]").isNotEmpty())
                .andExpect(jsonPath(availability + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(availability + ".responses['404'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(availability + ".responses['401']").doesNotExist())
                .andExpect(jsonPath(availability + ".responses['403']").doesNotExist());
    }

    @Test
    void apiDocs_bookingDocumentsCreatedAndEveryDeclaredErrorWithProblemBody() throws Exception {
        String book = "$.paths['/api/appointments'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(book + ".responses['201'].content['application/json']").exists())
                .andExpect(jsonPath(book + ".responses['400'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(book + ".responses['404'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(book + ".responses['409'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF));
    }

    @Test
    void apiDocs_securedOperationsInheritBearerAndGetBareAuthErrors() throws Exception {
        String list = "$.paths['/api/appointments'].get";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.security[0].bearer-jwt").exists())
                .andExpect(jsonPath(list + ".security").doesNotExist())
                // Spring Security's own 401/403 have an empty body, so the defaults carry no content
                .andExpect(jsonPath(list + ".responses['401']").exists())
                .andExpect(jsonPath(list + ".responses['401'].content").doesNotExist())
                .andExpect(jsonPath(list + ".responses['403']").exists())
                .andExpect(jsonPath(list + ".responses['403'].content").doesNotExist())
                // taking no input, the operation must not pick up a default 400
                .andExpect(jsonPath(list + ".responses['400']").doesNotExist());
    }

    @Test
    void apiDocs_cancelHasProblemBodyOnDeclaredOwnership403() throws Exception {
        String cancel = "$.paths['/api/appointments/{id}/cancel'].put";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(cancel + ".responses['403'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(cancel + ".responses['404'].content['application/problem+json']"
                        + ".schema['$ref']").value(PROBLEM_REF))
                .andExpect(jsonPath(cancel + ".responses['401'].content").doesNotExist());
    }

    @Test
    void apiDocs_injectedJwtPrincipalIsNeverDocumentedAsAParameter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths..parameters[?(@.name == 'jwt')]").isEmpty());
    }
}
