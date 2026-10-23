package com.healthtech.appointment.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

// Unit tests for ClockConfig pinning the Clock bean to the configured clinic time zone.
class ClockConfigTest {

    @Test
    void clock_defaultClinicTimeZone_pinsToEuropeBerlin() {
        // Arrange
        ClockConfig config = new ClockConfig();

        // Act
        Clock clock = config.clock("Europe/Berlin");

        // Assert
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void clock_customClinicTimeZone_pinsToConfiguredZone() {
        // Arrange
        ClockConfig config = new ClockConfig();

        // Act
        Clock clock = config.clock("America/New_York");

        // Assert
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    void clock_independentOfHostSystemDefaultZone() {
        // Arrange
        ClockConfig config = new ClockConfig();
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));

            // Act
            Clock clock = config.clock("Europe/Berlin");

            // Assert
            assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
