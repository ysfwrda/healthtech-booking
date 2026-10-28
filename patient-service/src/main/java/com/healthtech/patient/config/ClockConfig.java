package com.healthtech.patient.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Business logic asks this Clock for "now" instead of reading the system clock directly, so
// time-dependent writes (the last-login timestamp) can be tested at a fixed instant.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
