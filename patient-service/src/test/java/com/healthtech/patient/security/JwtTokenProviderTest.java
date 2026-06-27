package com.healthtech.patient.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    public static final int EXPIRATION_SECONDS = 3600;
    private JwtTokenProvider jwtTokenProvider;
    private RSAPublicKey publicKey;

    @BeforeEach
    public void setup() throws NoSuchAlgorithmException {
        KeyPairGenerator rsaKeyPairGenerator = KeyPairGenerator.getInstance("RSA");
        rsaKeyPairGenerator.initialize(2048);
        KeyPair keyPair = rsaKeyPairGenerator.generateKeyPair();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();
        RsaKeyProperties rsaKeyProperties = new RsaKeyProperties(publicKey, (RSAPrivateKey) keyPair.getPrivate());
        this.jwtTokenProvider = new JwtTokenProvider(rsaKeyProperties, EXPIRATION_SECONDS);
    }

    @Test
    void generateToken_returnsJwtTokenWithThreeSegments() {
        UUID patientId = UUID.randomUUID();
        String token = jwtTokenProvider.generateToken(patientId);
        assertThat(token).isNotNull();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void generateToken_signsWithPrivateKeyAndContainsCorrectClaims(){
        UUID patientId = UUID.randomUUID();
        String token = jwtTokenProvider.generateToken(patientId);
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(patientId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("PATIENT");
    }

    @Test
    void generateToken_setsExpiryApproximatelyOneHourAhead(){
        UUID patientId = UUID.randomUUID();
        Instant before = Instant.now();
        String token = jwtTokenProvider.generateToken(patientId);
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getExpiration().toInstant())
                .isBetween(before.plusSeconds(EXPIRATION_SECONDS - 10), before.plusSeconds(EXPIRATION_SECONDS + 10));
    }
}