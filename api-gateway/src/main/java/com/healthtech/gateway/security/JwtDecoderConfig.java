package com.healthtech.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Split from SecurityConfig so a test can supply its own JwtDecoder without pulling in
// RsaKeyProperties, mirroring the split already used by the domain services.
@Configuration
@EnableConfigurationProperties(RsaKeyProperties.class)
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyProperties keys) {
        return NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
    }
}
