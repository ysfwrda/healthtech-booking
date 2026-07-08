package com.healthtech.gateway.config;

import com.healthtech.gateway.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.function.Function;

@Configuration
public class GatewayConfig {

    @Value("${app.gateway.appointment-service-uri}")
    private String appointmentServiceUri;

    @Value("${app.gateway.patient-service-uri}")
    private String patientServiceUri;

    @Value("${app.gateway.doctor-service-uri}")
    private String doctorServiceUri;

    // CorrelationIdFilter already resolved (or generated) the id and stored it in MDC before
    // routing reaches here. This propagates that same id onto the outbound proxied request,
    // overwriting any incoming header so a freshly generated id also reaches the downstream service.
    private Function<ServerRequest, ServerRequest> propagateCorrelationId() {
        return request -> ServerRequest.from(request)
                .headers(headers -> headers.set(CorrelationIdFilter.CORRELATION_ID_HEADER, MDC.get(CorrelationIdFilter.MDC_KEY)))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> appointmentServiceRoute() {
        return GatewayRouterFunctions.route("appointment-service")
                .route(GatewayRequestPredicates.path("/api/appointments/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(appointmentServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> availabilityServiceRoute() {
        return GatewayRouterFunctions.route("availability-service")
                .route(GatewayRequestPredicates.path("/api/availability/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(appointmentServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> authServiceRoute() {
        return GatewayRouterFunctions.route("auth-service")
                .route(GatewayRequestPredicates.path("/api/auth/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(patientServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> patientServiceRoute() {
        return GatewayRouterFunctions.route("patient-service")
                .route(GatewayRequestPredicates.path("/api/patients/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(patientServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> doctorServiceRoute() {
        return GatewayRouterFunctions.route("doctor-service")
                .route(GatewayRequestPredicates.path("/api/doctors/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(doctorServiceUri))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> specialtyServiceRoute() {
        return GatewayRouterFunctions.route("specialty-service")
                .route(GatewayRequestPredicates.path("/api/specialties/**"),
                        HandlerFunctions.http())
                .before(propagateCorrelationId())
                .before(BeforeFilterFunctions.uri(doctorServiceUri))
                .build();
    }
}