package com.healthtech.appointment.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.VacationPeriod;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidPatient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The doctor's appointment API end to end against real Postgres: the list query, the actions, the outbox and the roles.
class DoctorAppointmentIntegrationTest extends AbstractAppointmentApiTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);
    private static final String BASE = "/api/appointments/doctor";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static LocalDateTime slot(int hour, int minute) {
        return LocalDateTime.of(NEXT_WEEK, LocalTime.of(hour, minute));
    }

    private ResponseEntity<String> get(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> put(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(headers), String.class);
    }

    private List<String> listIds(String url, UUID doctorId) throws Exception {
        ResponseEntity<String> response = get(doctorHeaders(doctorId), url);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(response.getBody())) {
            ids.add(node.get("id").asText());
        }
        return ids;
    }

    // -- booking status ----------------------------------------------------------

    @Test
    void booking_withARegularDoctor_startsPendingAndWithASeededDoctorStartsConfirmed() {
        // Arrange
        ValidDoctor regular = seedDoctor(NEXT_WEEK, false);
        ValidDoctor seeded = seedDoctor(NEXT_WEEK, true);
        ValidPatient patient = seedPatient();

        // Act
        ResponseEntity<AppointmentResponse> pending = postBooking(patientHeaders(patient.getPatientId()),
                regular.getDoctorId(), slot(9, 0), AppointmentType.FOLLOW_UP, null);
        ResponseEntity<AppointmentResponse> confirmed = postBooking(patientHeaders(patient.getPatientId()),
                seeded.getDoctorId(), slot(9, 0), AppointmentType.FOLLOW_UP, null);

        // Assert
        assertThat(pending.getBody().getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(confirmed.getBody().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(outboxRows(pending.getBody().getId(), "appointment.booked")).hasSize(1);
        assertThat(outboxRows(confirmed.getBody().getId(), "appointment.booked")).hasSize(1);
    }

    @Test
    void booking_onAVacationDay_returns400AndWritesNothing() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        doctor.setVacations(Set.of(VacationPeriod.builder().startDate(NEXT_WEEK).endDate(NEXT_WEEK).build()));
        validDoctorRepository.save(doctor);
        ValidPatient patient = seedPatient();
        long rowsBefore = outboxRepository.count();

        // Act
        ResponseEntity<String> response = restTemplate.exchange("/api/appointments", HttpMethod.POST,
                new HttpEntity<>(com.healthtech.appointment.dto.AppointmentRequest.builder()
                        .doctorId(doctor.getDoctorId()).dateTime(slot(9, 0)).type(AppointmentType.FOLLOW_UP).build(),
                        patientHeaders(patient.getPatientId())), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Slot On Vacation");
        assertThat(outboxRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    void availability_onAVacationDay_offersNoSlots() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        doctor.setVacations(Set.of(VacationPeriod.builder().startDate(NEXT_WEEK).endDate(NEXT_WEEK).build()));
        validDoctorRepository.save(doctor);

        // Act
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/availability?doctorId=" + doctor.getDoctorId() + "&date=" + NEXT_WEEK, String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("availableSlots")).isEmpty();
    }

    // -- list --------------------------------------------------------------------

    @Test
    void list_returnsOnlyTheDoctorsOwnAppointmentsEarliestFirstWithPatientNames() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        ValidDoctor otherDoctor = seedDoctor(NEXT_WEEK);
        ValidPatient patient = seedPatient();
        UUID late = book(patient, doctor, slot(15, 0), AppointmentType.FOLLOW_UP);
        UUID early = book(patient, doctor, slot(9, 30), AppointmentType.FOLLOW_UP);
        book(patient, otherDoctor, slot(10, 0), AppointmentType.FOLLOW_UP);

        // Act
        ResponseEntity<String> response = get(doctorHeaders(doctor.getDoctorId()), BASE);

        // Assert
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("id").asText()).isEqualTo(early.toString());
        assertThat(body.get(1).get("id").asText()).isEqualTo(late.toString());
        assertThat(body.get(0).get("patientName").asText()).isEqualTo("Valid Patient");
        assertThat(body.get(0).get("patientEmail").asText()).isEqualTo("valid.patient@example.com");
        assertThat(body.get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void list_fromAndToAreWholeDaysBothInclusive() throws Exception {
        // Arrange: the doctor works every day of the week, appointments on three consecutive days
        ValidDoctor doctor = openEveryDay();
        ValidPatient patient = seedPatient();
        LocalDate day = NEXT_WEEK;
        UUID first = book(patient, doctor, day.atTime(9, 0), AppointmentType.FOLLOW_UP);
        UUID middleStart = book(patient, doctor, day.plusDays(1).atTime(9, 0), AppointmentType.FOLLOW_UP);
        UUID middleEnd = book(patient, doctor, day.plusDays(1).atTime(16, 30), AppointmentType.FOLLOW_UP);
        UUID last = book(patient, doctor, day.plusDays(2).atTime(9, 0), AppointmentType.FOLLOW_UP);

        // Act & Assert
        assertThat(listIds(BASE + "?from=" + day.plusDays(1) + "&to=" + day.plusDays(1), doctor.getDoctorId()))
                .containsExactly(middleStart.toString(), middleEnd.toString());
        assertThat(listIds(BASE + "?from=" + day + "&to=" + day.plusDays(1), doctor.getDoctorId()))
                .containsExactly(first.toString(), middleStart.toString(), middleEnd.toString());
        assertThat(listIds(BASE + "?from=" + day.plusDays(2), doctor.getDoctorId())).containsExactly(last.toString());
        assertThat(listIds(BASE + "?to=" + day, doctor.getDoctorId())).containsExactly(first.toString());
    }

    @Test
    void list_filtersByOneOrSeveralStatuses() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        ValidPatient patient = seedPatient();
        UUID pending = book(patient, doctor, slot(9, 0), AppointmentType.FOLLOW_UP);
        UUID confirmed = book(patient, doctor, slot(10, 0), AppointmentType.FOLLOW_UP);
        UUID cancelled = book(patient, doctor, slot(11, 0), AppointmentType.FOLLOW_UP);
        assertThat(put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + confirmed + "/confirm").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + cancelled + "/cancel").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Act & Assert
        assertThat(listIds(BASE + "?status=PENDING", doctor.getDoctorId())).containsExactly(pending.toString());
        assertThat(listIds(BASE + "?status=PENDING&status=CONFIRMED", doctor.getDoctorId()))
                .containsExactly(pending.toString(), confirmed.toString());
        assertThat(listIds(BASE, doctor.getDoctorId())).hasSize(3);
    }

    @Test
    void list_fromAfterTo_returns400() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);

        // Act
        ResponseEntity<String> response = get(doctorHeaders(doctor.getDoctorId()),
                BASE + "?from=" + NEXT_WEEK.plusDays(1) + "&to=" + NEXT_WEEK);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Invalid Date Range");
    }

    @Test
    void list_noTokenAndPatientToken_areRejected() {
        // Act
        ResponseEntity<String> noToken = restTemplate.getForEntity(BASE, String.class);
        ResponseEntity<String> patient = get(patientHeaders(UUID.randomUUID()), BASE);

        // Assert
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(patient.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -- confirm, cancel, change -------------------------------------------------

    @Test
    void confirm_pendingAppointment_becomesConfirmedAndQueuesOneConfirmedEvent() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        UUID id = book(seedPatient(), doctor, slot(9, 0), AppointmentType.FOLLOW_UP);

        // Act
        ResponseEntity<String> first = put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/confirm");
        ResponseEntity<String> repeated = put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/confirm");

        // Assert
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(appointmentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(outboxRows(id, "appointment.confirmed")).hasSize(1);
    }

    @Test
    void confirm_cancelledAppointment_returns409() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        UUID id = book(seedPatient(), doctor, slot(9, 0), AppointmentType.FOLLOW_UP);
        put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/cancel");

        // Act
        ResponseEntity<String> response = put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/confirm");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Appointment Not Confirmable");
        assertThat(outboxRows(id, "appointment.confirmed")).isEmpty();
    }

    @Test
    void cancel_byTheDoctor_freesTheSlotAndQueuesACancelledEventMarkedAsDoctor() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0), AppointmentType.FOLLOW_UP);

        // Act
        ResponseEntity<String> response = put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/cancel");
        ResponseEntity<AppointmentResponse> rebooked = postBooking(patientHeaders(patient.getPatientId()),
                doctor.getDoctorId(), slot(9, 0), AppointmentType.FOLLOW_UP, null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rebooked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<OutboxMessage> rows = outboxRows(id, "appointment.cancelled");
        assertThat(rows).hasSize(1);
        assertThat(objectMapper.readTree(rows.get(0).getPayload()).get("cancelledBy").asText()).isEqualTo("DOCTOR");
    }

    @Test
    void cancel_aSecondTime_isANoOpWithoutAnotherEvent() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        UUID id = book(seedPatient(), doctor, slot(9, 0), AppointmentType.FOLLOW_UP);
        put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/cancel");

        // Act
        ResponseEntity<String> again = put(doctorHeaders(doctor.getDoctorId()), BASE + "/" + id + "/cancel");

        // Assert
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxRows(id, "appointment.cancelled")).hasSize(1);
    }

    @Test
    void change_movesTheAppointmentFreesTheOldSlotAndQueuesAChangedEventMarkedAsDoctor() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0), AppointmentType.FOLLOW_UP);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(BASE + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(DoctorAppointmentUpdateRequest.builder().dateTime(slot(11, 0)).build(),
                        doctorHeaders(doctor.getDoctorId())), String.class);
        ResponseEntity<AppointmentResponse> intoOldSlot = postBooking(patientHeaders(patient.getPatientId()),
                doctor.getDoctorId(), slot(9, 0), AppointmentType.FOLLOW_UP, null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Appointment moved = appointmentRepository.findById(id).orElseThrow();
        assertThat(moved.getDateTime()).isEqualTo(slot(11, 0));
        assertThat(moved.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(intoOldSlot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<OutboxMessage> rows = outboxRows(id, "appointment.changed");
        assertThat(rows).hasSize(1);
        assertThat(objectMapper.readTree(rows.get(0).getPayload()).get("changedBy").asText()).isEqualTo("DOCTOR");
    }

    @Test
    void change_ontoATakenSlot_returns409AndKeepsTheOldTime() {
        // Arrange
        ValidDoctor doctor = seedDoctor(NEXT_WEEK);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0), AppointmentType.FOLLOW_UP);
        book(patient, doctor, slot(10, 0), AppointmentType.FOLLOW_UP);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(BASE + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(DoctorAppointmentUpdateRequest.builder().dateTime(slot(10, 0)).build(),
                        doctorHeaders(doctor.getDoctorId())), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(appointmentRepository.findById(id).orElseThrow().getDateTime()).isEqualTo(slot(9, 0));
        assertThat(outboxRows(id, "appointment.changed")).isEmpty();
    }

    @Test
    void actions_onAnotherDoctorsAppointment_return403AndChangeNothing() {
        // Arrange
        ValidDoctor owner = seedDoctor(NEXT_WEEK);
        ValidDoctor other = seedDoctor(NEXT_WEEK);
        UUID id = book(seedPatient(), owner, slot(9, 0), AppointmentType.FOLLOW_UP);
        HttpHeaders otherDoctor = doctorHeaders(other.getDoctorId());

        // Act
        ResponseEntity<String> confirm = put(otherDoctor, BASE + "/" + id + "/confirm");
        ResponseEntity<String> cancel = put(otherDoctor, BASE + "/" + id + "/cancel");
        ResponseEntity<String> change = restTemplate.exchange(BASE + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(DoctorAppointmentUpdateRequest.builder().type(AppointmentType.VACCINATION).build(),
                        otherDoctor), String.class);

        // Assert
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Appointment unchanged = appointmentRepository.findById(id).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(unchanged.getType()).isEqualTo(AppointmentType.FOLLOW_UP);
    }

    @Test
    void actions_onAnUnknownAppointment_return404() {
        // Arrange
        HttpHeaders doctor = doctorHeaders(UUID.randomUUID());
        UUID unknown = UUID.randomUUID();

        // Act & Assert
        assertThat(put(doctor, BASE + "/" + unknown + "/confirm").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(doctor, BASE + "/" + unknown + "/cancel").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void doctorTokenOnPatientEndpoints_isRejected() {
        // Act
        ResponseEntity<String> response = restTemplate.exchange("/api/appointments", HttpMethod.GET,
                new HttpEntity<>(doctorHeaders(UUID.randomUUID())), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ValidDoctor openEveryDay() {
        Set<OpeningHours> everyDay = new java.util.HashSet<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            everyDay.add(OpeningHours.builder().dayOfWeek(day)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build());
        }
        return validDoctorRepository.save(ValidDoctor.builder().doctorId(UUID.randomUUID())
                .firstName("Always").lastName("Open").openingHours(everyDay).build());
    }
}
