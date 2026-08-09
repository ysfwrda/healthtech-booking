package com.healthtech.appointment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;

// Authorization rules only. The JwtDecoder bean (and the RsaKeyProperties it needs,
// which requires real PEM files) lives in JwtDecoderConfig, not here, so a
// @WebMvcTest slice can @Import(SecurityConfig.class) to get the real rules while
// supplying its own mocked JwtDecoder bean, without ever reading a key from disk.
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/availability/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole("PATIENT")
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint())
                );

        return http.build();
    }

    // Under the shared RS256 key pair (ADR-004), the role claim - not the signature - is
    // what distinguishes a PATIENT token from a DOCTOR token. This maps that claim onto a
    // ROLE_ authority so hasRole("PATIENT") above can enforce it, replacing the controller's
    // former manual role check. Exposed (not private) so tests can drive the exact same
    // authorities the app wires, rather than reimplementing the mapping and risking drift.
    public Converter<Jwt, Collection<GrantedAuthority>> roleAuthoritiesConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return List.of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        };
    }

    private JwtAuthenticationConverter roleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleAuthoritiesConverter());
        return converter;
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, authException) -> {
            log.warn("Authentication rejected for {} {}: {}", request.getMethod(), request.getRequestURI(), authException.getMessage());
            delegate.commence(request, response, authException);
        };
    }
}
