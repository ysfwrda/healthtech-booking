package com.healthtech.doctor.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.interfaces.RSAPublicKey;

@ConfigurationProperties(prefix = "app.jwt")
public record RsaKeyProperties(RSAPublicKey publicKey) {
}
