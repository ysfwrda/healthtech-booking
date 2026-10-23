package com.healthtech.doctor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Business logic asks this Clock for "today" instead of reading the system clock, so date rules
// (a vacation cannot end in the past) can be tested at a fixed instant.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
