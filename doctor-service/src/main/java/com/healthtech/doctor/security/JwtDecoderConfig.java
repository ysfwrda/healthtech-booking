package com.healthtech.doctor.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Split from SecurityConfig so a @WebMvcTest slice can import just the authorization
// rules (SecurityConfig) without pulling in RsaKeyProperties, which requires reading
// real PEM key material from disk. @EnableConfigurationProperties here, rather than on
// the main application class, means the properties bean is only bound when this
// configuration class is actually loaded, never as a side effect of a slice test.
@Configuration
@EnableConfigurationProperties(RsaKeyProperties.class)
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyProperties rsaKeyProperties) {
        return NimbusJwtDecoder.withPublicKey(rsaKeyProperties.publicKey()).build();
    }
}
