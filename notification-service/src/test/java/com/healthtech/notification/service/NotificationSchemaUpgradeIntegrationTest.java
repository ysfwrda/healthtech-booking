package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.domain.NotificationType;
import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.event.AppointmentChanged;
import com.healthtech.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Starts the service against a database that already has the notification table from before event_id and
// APPOINTMENT_CHANGED existed, as an upgraded deployment would, and checks the schema script fixes it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NotificationSchemaUpgradeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("old-notification-schema.sql")
            .withStartupTimeout(Duration.ofMinutes(2));

    // src/test/resources/application.yaml is the H2 setup: switch to the production dialect, keep the old
    // table (update, not create-drop) and run schema-postgresql.sql after Hibernate as application.yaml does.
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "true");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.platform", () -> "postgresql");
    }

    @Autowired
    NotificationService notificationService;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private long rowsFor(UUID appointmentId) {
        return notificationRepository.findAll().stream()
                .filter(n -> appointmentId.equals(n.getAppointmentId()))
                .count();
    }

    private static AppointmentChanged changed(UUID eventId, UUID appointmentId) {
        return AppointmentChanged.builder().eventId(eventId).appointmentId(appointmentId)
                .patientId(UUID.randomUUID()).doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3)).build();
    }

    @Test
    void changedEvent_onTableCreatedBeforeTheNewTypeExisted_isStored() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();

        // Act
        notificationService.record(changed(UUID.randomUUID(), appointmentId));

        // Assert
        Notification stored = notificationRepository.findAll().stream()
                .filter(n -> appointmentId.equals(n.getAppointmentId())).findFirst().orElseThrow();
        assertThat(stored.getType()).isEqualTo(NotificationType.APPOINTMENT_CHANGED);
    }

    @Test
    void oldTypeCheckConstraint_afterStartup_isGone() {
        // Act
        Integer typeChecks = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conname = 'notification_type_check'", Integer.class);

        // Assert
        assertThat(typeChecks).isZero();
    }

    @Test
    void changedEvent_deliveredTwice_isStoredOnce() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        AppointmentChanged event = changed(UUID.randomUUID(), appointmentId);

        // Act
        notificationService.record(event);
        notificationService.record(event);

        // Assert
        assertThat(rowsFor(appointmentId)).isEqualTo(1);
    }

    @Test
    void bookedEvent_deliveredTwice_isStoredOnce() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        AppointmentBooked event = AppointmentBooked.builder().eventId(UUID.randomUUID()).appointmentId(appointmentId)
                .patientId(UUID.randomUUID()).doctorId(UUID.randomUUID()).dateTime(LocalDateTime.now().plusDays(3)).build();

        // Act
        notificationService.record(event);
        notificationService.record(event);

        // Assert
        assertThat(rowsFor(appointmentId)).isEqualTo(1);
    }

    @Test
    void cancelledEvent_deliveredTwice_isStoredOnce() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        AppointmentCancelled event = AppointmentCancelled.builder().eventId(UUID.randomUUID()).appointmentId(appointmentId)
                .patientId(UUID.randomUUID()).doctorId(UUID.randomUUID()).dateTime(LocalDateTime.now().plusDays(3)).build();

        // Act
        notificationService.record(event);
        notificationService.record(event);

        // Assert
        assertThat(rowsFor(appointmentId)).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsForOneAppointment_areBothStored() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();

        // Act
        notificationService.record(changed(UUID.randomUUID(), appointmentId));
        notificationService.record(changed(UUID.randomUUID(), appointmentId));

        // Assert
        assertThat(rowsFor(appointmentId)).isEqualTo(2);
    }
}
