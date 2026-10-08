package com.healthtech.patient.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String BEARER_SCHEME = "bearer-jwt";

    // Defaults to "/" (the origin serving the UI). Override to point "Try it out" at the gateway.
    @Value("${app.openapi.server-url:/}")
    private String serverUrl;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Patient Service API")
                        .description("Patient registration, login and profiles.\n\n**Authentication:** the profile endpoint requires a patient JWT; registration and login are public. Obtain a token via POST /api/auth/login (or /api/auth/register) on this service, click **Authorize** and paste the token (without the \"Bearer \" prefix). Operations with a lock icon require it.")
                        .version("v1"))
                .addServersItem(new Server().url(serverUrl))
                .components(new Components()
                        .addSchemas(OpenApiErrorCustomizer.PROBLEM_DETAIL, OpenApiErrorCustomizer.problemDetailSchema())
                        .addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT from the login endpoint; send as \"Authorization: Bearer <token>\".")))
                // Applied to every operation; public endpoints opt out with @SecurityRequirements.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
