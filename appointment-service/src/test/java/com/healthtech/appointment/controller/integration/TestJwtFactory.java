package com.healthtech.appointment.controller.integration;

import io.jsonwebtoken.Jwts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public final class TestJwtFactory {

    private TestJwtFactory() {
    }

    public static String patientToken(UUID patientId, RSAPrivateKey privateKey) {
        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(patientId.toString())
                .claim("role", "PATIENT")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }

    // Signed under the same shared key pair as patientToken (ADR-004): only the role claim
    // distinguishes it. Used to prove a DOCTOR token is rejected by role, not by signature.
    public static String doctorToken(UUID doctorId, RSAPrivateKey privateKey) {
        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(doctorId.toString())
                .claim("role", "DOCTOR")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }

    public static RSAPrivateKey loadPrivateKey(Path pemPath) throws Exception {
        String pem = Files.readString(pemPath)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(spec);
    }
}
