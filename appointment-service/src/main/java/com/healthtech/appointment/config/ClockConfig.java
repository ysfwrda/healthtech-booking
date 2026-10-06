package com.healthtech.appointment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Business logic asks this Clock for "now" instead of reading the system clock directly, so
// time-dependent rules (hiding past slots, cancellation timestamps) can be tested at a fixed instant.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
