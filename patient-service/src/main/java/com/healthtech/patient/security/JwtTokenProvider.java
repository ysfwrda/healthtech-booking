package com.healthtech.patient.security;

import io.jsonwebtoken.Jwts;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenProvider {
    private final RSAPrivateKey privateKey;

    @Getter
    private final long expirationSeconds;

    public JwtTokenProvider(RsaKeyProperties rsaKeyProperties,
                            @Value("${app.jwt.expiration}") long expirationSeconds) {
        this.privateKey = rsaKeyProperties.privateKey();
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(UUID patientId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(patientId.toString())
                .claim("role", "PATIENT")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }
}
