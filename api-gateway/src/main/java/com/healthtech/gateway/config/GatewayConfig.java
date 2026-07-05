package com.healthtech.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class GatewayConfig {

    @Value("${app.gateway.appointment-service-uri}")
    private String appointmentServiceUri;

    @Value("${app.gateway.patient-service-uri}")
    private String patientServiceUri;

    @Value("${app.gateway.doctor-service-uri}")
    private String doctorServiceUri;

    @Bean
    public RouterFunction<ServerResponse> appointmentServiceRoute() {
        return GatewayRouterFunctions.route("appointment-service")
                .route(GatewayRequestPredicates.path("/api/appointments/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(appointmentServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> availabilityServiceRoute() {
        return GatewayRouterFunctions.route("availability-service")
                .route(GatewayRequestPredicates.path("/api/availability/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(appointmentServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> authServiceRoute() {
        return GatewayRouterFunctions.route("auth-service")
                .route(GatewayRequestPredicates.path("/api/auth/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(patientServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> patientServiceRoute() {
        return GatewayRouterFunctions.route("patient-service")
                .route(GatewayRequestPredicates.path("/api/patients/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(patientServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> doctorServiceRoute() {
        return GatewayRouterFunctions.route("doctor-service")
                .route(GatewayRequestPredicates.path("/api/doctors/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(doctorServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> specialtyServiceRoute() {
        return GatewayRouterFunctions.route("specialty-service")
                .route(GatewayRequestPredicates.path("/api/specialties/**"),
                        HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(doctorServiceUri))
                .build();
    }
}