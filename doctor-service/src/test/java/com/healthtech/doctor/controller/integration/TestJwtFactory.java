package com.healthtech.doctor.controller.integration;

import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

// doctor-service is a resource server only (no login/issuance endpoint of its own), and
// createDoctor doesn't read any JWT claims - it only needs a validly-signed token. This
// mirrors appointment-service's TestJwtFactory pattern but is its own copy since these
// are independent Maven modules with no shared test-fixtures dependency.
public class TestJwtFactory {

    public static String anyValidToken(RSAPrivateKey privateKey) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(privateKey)
                .compact();
    }
}
