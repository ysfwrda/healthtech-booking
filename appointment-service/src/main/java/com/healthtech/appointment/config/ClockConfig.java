package com.healthtech.appointment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// Business logic asks this Clock for "now" instead of reading the system clock directly, so
// time-dependent rules (hiding past slots, cancellation timestamps, notice window) evaluate in the
// configured clinic time zone (Europe/Berlin) independent of host OS timezone, and can be tested at a fixed instant.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${appointment.clinic.time-zone:Europe/Berlin}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
