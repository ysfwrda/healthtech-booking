package com.healthtech.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.interfaces.RSAPublicKey;

// The gateway only ever validates; it never signs, so unlike the domain services'
// RsaKeyProperties, there is no privateKey here.
@ConfigurationProperties(prefix = "app.jwt")
public record RsaKeyProperties(RSAPublicKey publicKey) {
}
