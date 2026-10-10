package com.healthtech.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// CORS preflights from the frontend origin; no downstream service runs, preflights are answered at the edge.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCorsTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> preflight(String path, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, FRONTEND_ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
        return restTemplate.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    void preflight_patchOnAppointment_isAllowed() {
        // Act
        ResponseEntity<String> response = preflight("/api/appointments/3fa85f64-5717-4562-b3fc-2c963f66afa6", "PATCH");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.PATCH);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(FRONTEND_ORIGIN);
    }

    @Test
    void preflight_putOnCancel_isStillAllowed() {
        // Act
        ResponseEntity<String> response = preflight("/api/appointments/3fa85f64-5717-4562-b3fc-2c963f66afa6/cancel", "PUT");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.PUT);
    }

    @Test
    void preflight_methodNotInTheAllowList_isRejected() {
        // Act
        ResponseEntity<String> response = preflight("/api/appointments/3fa85f64-5717-4562-b3fc-2c963f66afa6", "TRACE");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
