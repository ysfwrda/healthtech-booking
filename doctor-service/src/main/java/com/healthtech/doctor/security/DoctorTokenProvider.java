package com.healthtech.doctor.security;

import io.jsonwebtoken.Jwts;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class DoctorTokenProvider {
    private final RSAPrivateKey privateKey;

    @Getter
    private final long expirationSeconds;

    public DoctorTokenProvider(RsaKeyProperties rsaKeyProperties,
                                @Value("${app.jwt.expiration}") long expirationSeconds) {
        this.privateKey = rsaKeyProperties.privateKey();
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(UUID doctorId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(doctorId.toString())
                .claim("role", "DOCTOR")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey)
                .compact();
    }
}
