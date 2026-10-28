package com.healthtech.appointment.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.domain.ConflictReason;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.event.DoctorAvailabilityUpdated;
import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.event.VacationPayload;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.consumer.DoctorEventConsumer;
import com.healthtech.appointment.service.AvailabilityChangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// What happens to booked appointments when a doctor's availability changes, and the locks that keep it correct,
// against real Postgres. The race tests hold a row lock from the test thread to make the interleaving deterministic.
class AvailabilityChangeIntegrationTest extends AbstractAppointmentApiTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AvailabilityChangeService availabilityChangeService;
    @Autowired
    private DoctorEventConsumer doctorEventConsumer;

    private static LocalDateTime slot(int hour, int minute) {
        return LocalDateTime.of(NEXT_WEEK, LocalTime.of(hour, minute));
    }

    private ValidDoctor doctorWithVersion(long version) {
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        doctor.setAvailabilityVersion(version);
        return validDoctorRepository.save(doctor);
    }

    private static OpeningHoursPayload hours(int startHour, int endHour) {
        return OpeningHoursPayload.builder().dayOfWeek(NEXT_WEEK.getDayOfWeek())
                .startTime(LocalTime.of(startHour, 0)).endTime(LocalTime.of(endHour, 0)).build();
    }

    private static DoctorAvailabilityUpdated snapshot(ValidDoctor doctor, long version, Set<OpeningHoursPayload> hours,
                                                      List<VacationPayload> vacations) {
        return DoctorAvailabilityUpdated.builder().eventId(UUID.randomUUID()).doctorId(doctor.getDoctorId())
                .openingHours(hours).vacations(vacations).version(version).build();
    }

    private Appointment reload(UUID id) {
        return appointmentRepository.findById(id).orElseThrow();
    }

    private static void waitFor(CompletableFuture<?> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    // -- flagging against the real database --------------------------------------

    @Test
    void newHoursThatExcludeAnAppointment_flagItQueueOneEventAndKeepTheStatus() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        UUID afternoon = book(patient, doctor, slot(14, 0), AppointmentType.FOLLOW_UP);
        UUID morning = book(patient, doctor, slot(10, 0), AppointmentType.FOLLOW_UP);

        // Act
        availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(9, 12)), List.of()));

        // Assert
        assertThat(reload(afternoon).getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
        assertThat(reload(afternoon).getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(reload(morning).getConflictReason()).isNull();
        List<OutboxMessage> rows = outboxRows(afternoon, "appointment.reschedule-required");
        assertThat(rows).hasSize(1);
        JsonNode event = objectMapper.readTree(rows.get(0).getPayload());
        assertThat(event.get("reason").asText()).isEqualTo("OUTSIDE_OPENING_HOURS");
        assertThat(event.get("patientId").asText()).isEqualTo(patient.getPatientId().toString());
        assertThat(rows.get(0).getPublishedAt()).isNull();
        assertThat(outboxRows(morning, "appointment.reschedule-required")).isEmpty();
    }

    @Test
    void theSameSnapshotTwice_flagsOnceAndNotifiesOnce() {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        UUID id = book(seedPatient(), doctor, slot(14, 0), AppointmentType.FOLLOW_UP);
        DoctorAvailabilityUpdated change = snapshot(doctor, 2, Set.of(hours(9, 12)), List.of());

        // Act
        availabilityChangeService.handle(change);
        availabilityChangeService.handle(change);

        // Assert
        assertThat(outboxRows(id, "appointment.reschedule-required")).hasSize(1);
    }

    @Test
    void vacationCoveringAnAppointment_flagsItAsVacationAndRevertingClearsItWithoutAnEvent() {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        UUID id = book(seedPatient(), doctor, slot(10, 0), AppointmentType.FOLLOW_UP);
        VacationPayload vacation = VacationPayload.builder().startDate(NEXT_WEEK).endDate(NEXT_WEEK).build();

        // Act
        availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(9, 17)), List.of(vacation)));
        ConflictReason whileOnVacation = reload(id).getConflictReason();
        availabilityChangeService.handle(snapshot(doctor, 3, Set.of(hours(9, 17)), List.of()));

        // Assert
        assertThat(whileOnVacation).isEqualTo(ConflictReason.DOCTOR_VACATION);
        assertThat(reload(id).getConflictReason()).isNull();
        assertThat(outboxRows(id, "appointment.reschedule-required")).hasSize(1);
    }

    @Test
    void cancelledAppointments_areNeverFlagged() {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, doctor, slot(14, 0), AppointmentType.FOLLOW_UP);
        putCancel(patientHeaders(patient.getPatientId()), id);

        // Act
        availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(9, 12)), List.of()));

        // Assert
        assertThat(reload(id).getConflictReason()).isNull();
        assertThat(reload(id).getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(outboxRows(id, "appointment.reschedule-required")).isEmpty();
    }

    @Test
    void anOlderSnapshot_changesNeitherTheReadModelNorTheFlags() {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(5);
        UUID id = book(seedPatient(), doctor, slot(14, 0), AppointmentType.FOLLOW_UP);

        // Act
        availabilityChangeService.handle(snapshot(doctor, 4, Set.of(hours(9, 12)), List.of()));

        // Assert
        ValidDoctor after = validDoctorRepository.findById(doctor.getDoctorId()).orElseThrow();
        assertThat(after.getAvailabilityVersion()).isEqualTo(5);
        assertThat(after.getOpeningHours()).extracting(h -> h.getEndTime()).containsExactly(LocalTime.of(17, 0));
        assertThat(reload(id).getConflictReason()).isNull();
    }

    @Test
    void aSnapshotForADoctorNotYetKnown_createsTheRow() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        ValidDoctor unsaved = ValidDoctor.builder().doctorId(doctorId).build();

        // Act
        availabilityChangeService.handle(snapshot(unsaved, 1, Set.of(hours(9, 12)), List.of()));

        // Assert
        ValidDoctor created = validDoctorRepository.findById(doctorId).orElseThrow();
        assertThat(created.getAvailabilityVersion()).isEqualTo(1);
        assertThat(created.getOpeningHours()).hasSize(1);
        assertThat(created.isAutoConfirmBookings()).isFalse();
    }

    // -- a patient or doctor change must not be undone by the re-evaluation ------

    @Test
    void aCancelCommittingWhileTheReEvaluationWaitsForTheAppointmentLock_isNotOverwritten() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        UUID id = book(seedPatient(), doctor, slot(14, 0), AppointmentType.FOLLOW_UP);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> reEvaluation = new CompletableFuture<>();

        // Act: the test holds the appointment row, cancels it, and only then lets the listener's write proceed
        tx.executeWithoutResult(status -> {
            Appointment locked = appointmentRepository.findByIdForUpdate(id).orElseThrow();
            reEvaluation.completeAsync(() -> {
                availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(9, 12)), List.of()));
                return null;
            });
            sleep(1500);
            locked.setStatus(AppointmentStatus.CANCELLED);
            appointmentRepository.save(locked);
        });
        waitFor(reEvaluation);

        // Assert
        assertThat(reload(id).getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(reload(id).getConflictReason()).isNull();
        assertThat(outboxRows(id, "appointment.reschedule-required")).isEmpty();
    }

    @Test
    void aPatientChangeRacingTheReEvaluationOnTheSameAppointment_bothFinishWithoutDeadlock() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, doctor, slot(10, 0), AppointmentType.FOLLOW_UP);

        // Act: 8 rounds, each moving the appointment while a new snapshot is applied
        List<CompletableFuture<?>> all = new java.util.ArrayList<>();
        for (int round = 0; round < 8; round++) {
            long version = 2 + round;
            LocalDateTime target = slot(round % 2 == 0 ? 11 : 10, 0);
            all.add(CompletableFuture.supplyAsync(() -> restTemplate.exchange("/api/appointments/" + id, HttpMethod.PATCH,
                    new HttpEntity<>(AppointmentUpdateRequest.builder().dateTime(target).build(),
                            patientHeaders(patient.getPatientId())), String.class)));
            all.add(CompletableFuture.runAsync(() -> availabilityChangeService.handle(
                    snapshot(doctor, version, Set.of(hours(9, 12)), List.of()))));
        }

        // Assert
        for (CompletableFuture<?> future : all) {
            Object result = future.get(60, TimeUnit.SECONDS);
            if (result instanceof ResponseEntity<?> response) {
                assertThat(response.getStatusCode().value()).isIn(200, 409);
            }
        }
        assertThat(reload(id).getStatus()).isNotEqualTo(AppointmentStatus.CANCELLED);
    }

    // -- the doctor row lock -----------------------------------------------------

    @Test
    void aBookingWaitingForAnAvailabilityChange_seesTheVacationAndIsRejected() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<ResponseEntity<String>> booking = new CompletableFuture<>();

        // Act: the availability change holds the doctor row; the booking arrives, waits, and must see the result
        tx.executeWithoutResult(status -> {
            ValidDoctor locked = validDoctorRepository.findByIdForUpdate(doctor.getDoctorId()).orElseThrow();
            locked.getVacations().add(com.healthtech.appointment.readmodel.VacationPeriod.builder()
                    .startDate(NEXT_WEEK).endDate(NEXT_WEEK).build());
            validDoctorRepository.saveAndFlush(locked);
            booking.completeAsync(() -> restTemplate.exchange("/api/appointments", HttpMethod.POST,
                    new HttpEntity<>(com.healthtech.appointment.dto.AppointmentRequest.builder()
                            .doctorId(doctor.getDoctorId()).dateTime(slot(10, 0)).type(AppointmentType.FOLLOW_UP).build(),
                            patientHeaders(patient.getPatientId())), String.class));
            sleep(1000);
        });
        ResponseEntity<String> response = booking.get(20, TimeUnit.SECONDS);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Slot On Vacation");
        assertThat(appointmentRepository.findAll().stream().filter(a -> a.getDoctorId().equals(doctor.getDoctorId())))
                .isEmpty();
    }

    @Test
    void aBookingThatCommittedBeforeTheChangeAppliesIsSeenByTheReEvaluation() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> change = new CompletableFuture<>();
        CompletableFuture<UUID> booking = new CompletableFuture<>();

        // Act: a booking holds the doctor row shared; the change must wait for it and then see its appointment
        tx.executeWithoutResult(status -> {
            validDoctorRepository.findByIdForShare(doctor.getDoctorId()).orElseThrow();
            change.completeAsync(() -> {
                availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(9, 12)), List.of()));
                return null;
            });
            sleep(1000);
            Appointment saved = appointmentRepository.saveAndFlush(Appointment.builder()
                    .patientId(patient.getPatientId()).doctorId(doctor.getDoctorId()).dateTime(slot(14, 0))
                    .duration(30).type(AppointmentType.FOLLOW_UP).status(AppointmentStatus.PENDING).build());
            booking.complete(saved.getId());
        });
        waitFor(change);

        // Assert
        assertThat(reload(booking.get()).getConflictReason()).isEqualTo(ConflictReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void aBookingWhoseDoctorRowStaysLockedForMoreThanThreeSeconds_gets503WithRetryAfter() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        ValidPatient patient = seedPatient();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<ResponseEntity<String>> booking = new CompletableFuture<>();

        // Act
        tx.executeWithoutResult(status -> {
            validDoctorRepository.findByIdForUpdate(doctor.getDoctorId()).orElseThrow();
            booking.completeAsync(() -> restTemplate.exchange("/api/appointments", HttpMethod.POST,
                    new HttpEntity<>(com.healthtech.appointment.dto.AppointmentRequest.builder()
                            .doctorId(doctor.getDoctorId()).dateTime(slot(10, 0)).type(AppointmentType.FOLLOW_UP).build(),
                            patientHeaders(patient.getPatientId())), String.class));
            sleep(4500);
        });
        ResponseEntity<String> response = booking.get(20, TimeUnit.SECONDS);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).contains("Appointment Busy");
    }

    // -- doctor.registered must not undo availability ----------------------------

    @Test
    void doctorRegisteredForAKnownDoctor_updatesNamesAndFlagButKeepsHoursVacationsAndVersion() {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        availabilityChangeService.handle(snapshot(doctor, 2, Set.of(hours(10, 11)),
                List.of(VacationPayload.builder().startDate(NEXT_WEEK).endDate(NEXT_WEEK).build())));
        DoctorRegistered registered = DoctorRegistered.builder().eventId(UUID.randomUUID()).doctorId(doctor.getDoctorId())
                .firstName("Renamed").lastName("Doctor").autoConfirmBookings(true)
                .openingHours(Set.of(OpeningHoursPayload.builder().dayOfWeek(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(20, 0)).build()))
                .build();

        // Act
        doctorEventConsumer.onDoctorRegistered(registered, null);

        // Assert
        ValidDoctor after = validDoctorRepository.findById(doctor.getDoctorId()).orElseThrow();
        assertThat(after.getFirstName()).isEqualTo("Renamed");
        assertThat(after.isAutoConfirmBookings()).isTrue();
        assertThat(after.getAvailabilityVersion()).isEqualTo(2);
        assertThat(after.getOpeningHours()).hasSize(1);
        assertThat(after.getOpeningHours().iterator().next().getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(after.getVacations()).hasSize(1);
    }

    @Test
    void doctorRegisteredForAnUnknownDoctor_createsTheRowWithHoursAndTheFlag() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        DoctorRegistered registered = DoctorRegistered.builder().eventId(UUID.randomUUID()).doctorId(doctorId)
                .firstName("New").lastName("Doctor").autoConfirmBookings(true)
                .openingHours(Set.of(hours(9, 17))).build();

        // Act
        doctorEventConsumer.onDoctorRegistered(registered, null);

        // Assert
        ValidDoctor created = validDoctorRepository.findById(doctorId).orElseThrow();
        assertThat(created.isAutoConfirmBookings()).isTrue();
        assertThat(created.getOpeningHours()).hasSize(1);
        assertThat(created.getAvailabilityVersion()).isNull();
    }

    @Test
    void doctorRegisteredRacingAnAvailabilityChange_cannotRegressTheVersion() throws Exception {
        // Arrange
        ValidDoctor doctor = doctorWithVersion(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> registered = new CompletableFuture<>();
        DoctorRegistered event = DoctorRegistered.builder().eventId(UUID.randomUUID()).doctorId(doctor.getDoctorId())
                .firstName("Valid").lastName("Doctor").autoConfirmBookings(true).build();

        // Act: the availability change holds the doctor row; the re-published registration waits for it
        tx.executeWithoutResult(status -> {
            ValidDoctor locked = validDoctorRepository.findByIdForUpdate(doctor.getDoctorId()).orElseThrow();
            registered.completeAsync(() -> {
                doctorEventConsumer.onDoctorRegistered(event, null);
                return null;
            });
            sleep(1000);
            locked.setAvailabilityVersion(7L);
            validDoctorRepository.save(locked);
        });
        waitFor(registered);

        // Assert
        ValidDoctor after = validDoctorRepository.findById(doctor.getDoctorId()).orElseThrow();
        assertThat(after.getAvailabilityVersion()).isEqualTo(7);
        assertThat(after.isAutoConfirmBookings()).isTrue();
    }

    @Test
    void aFailingSnapshot_isNotSwallowed() {
        // Arrange: a null doctor id cannot be applied
        DoctorAvailabilityUpdated broken = DoctorAvailabilityUpdated.builder().eventId(UUID.randomUUID())
                .version(1).openingHours(Set.of()).vacations(List.of()).build();

        // Act & Assert
        assertThatThrownBy(() -> availabilityChangeService.handle(broken)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void autoConfirmColumn_hasADatabaseDefaultSoRowsFromBeforeTheFeatureUpgrade() {
        // Act
        String columnDefault = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_name = 'valid_doctor' and column_name = 'auto_confirm_bookings'", String.class);
        String nullable = jdbcTemplate.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'valid_doctor' and column_name = 'auto_confirm_bookings'", String.class);

        // Assert: Hibernate adds the column to an existing table with this default, so old rows read as false
        assertThat(columnDefault).isEqualTo("false");
        assertThat(nullable).isEqualTo("NO");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
