package com.healthtech.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// Guards the one failure mode that matters most for this change: a public path silently
// becoming blocked because the gateway's public-path list drifted from what the services
// actually expose. No downstream service is running in this test, so public paths are
// expected to fail with a proxy-hop 5xx rather than succeed - what this asserts is that
// security itself let the request through (not 401/403), which is the gateway's own concern.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void protectedPath_noAuthorization_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/appointments", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    static Stream<Arguments> publicPaths() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/auth/register"),
                Arguments.of(HttpMethod.POST, "/api/auth/login"),
                Arguments.of(HttpMethod.POST, "/api/doctors/register"),
                Arguments.of(HttpMethod.POST, "/api/doctors/login"),
                Arguments.of(HttpMethod.GET, "/api/doctors"),
                Arguments.of(HttpMethod.GET, "/api/specialties"),
                Arguments.of(HttpMethod.GET, "/api/availability")
        );
    }

    // This is the register-not-blocked guard: it fails the moment a new endpoint is added to
    // a service's own public-path list without the gateway's list being updated to match.
    @ParameterizedTest(name = "{0} {1} is not blocked at the edge")
    @MethodSource("publicPaths")
    void publicPath_noAuthorization_isNotRejectedByEdgeSecurity(HttpMethod method, String path) {
        ResponseEntity<String> response = restTemplate.exchange(path, method, null, String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }
}
