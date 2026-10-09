# Design: Patient changes an appointment

Status: draft

## Task
Patient should be able to change their appointment up to 48 hours before their appointment. They might change the purpose and or the time. The time should remain inside the availabillity of the doctor.
The work should be done on two steps::
1- Backend with a new POST endpoint for appointmentController, or maybe PATCH, tell me what you choose and why
2- Frontend: the user should have a button change next to cancel button.

Clarified with the user: the 48h applies to the original appointment only; the new time only has to be in the future. Any non-cancelled appointment can be changed. The notice period is a config property.

## Approach
appointment-service gets `PATCH /api/appointments/{id}` with body `{type?, dateTime?, notes?}` (at least one field; null/absent = unchanged; `notes: ""` clears the notes), returning the updated `AppointmentResponse` (200). `AppointmentService.updateAppointment(id, request, patientId)`, `@Transactional`:
1. Load the appointment (404), check ownership (403).
2. Status `CANCELLED` -> 409 `Appointment Not Changeable`.
3. If `appointment.dateTime` is less than `appointment.change.min-notice-hours` (default 48) after `LocalDateTime.now(clock)` -> 409 `Change Window Closed`. Exactly 48h is allowed.
4. If a different `dateTime` is given: every `BookingRule` runs against it with the `ValidDoctor` from the read model (not in the past via `NotInPastRule`, 30-minute alignment, doctor's opening hours; a past time gives the existing 400 `Slot In The Past`). The row is updated with `saveAndFlush`; a `DataIntegrityViolationException` from `ux_active_appointment` becomes `SlotAlreadyBookedException` (409).
5. `type` / `notes` are applied. Doctor, patient, duration and status never change.

Frontend: a "Change" button beside "Cancel" in `MyAppointmentsPage` opens an inline form (type, notes, date + slot grid from `GET /api/availability`). The button stays enabled for every non-cancelled appointment and the 409 `Change Window Closed` message is shown, so the UI never disagrees with the configured notice period.

PATCH rather than POST/PUT: the client sends only the fields that change, and PUT would imply replacing the whole resource (doctor, patient, status) while the existing `PUT /{id}/cancel` is an action. POST `/reschedule` would only cover the time, not the purpose.

## Alternatives considered
- `POST /{id}/reschedule`: covers time only; purpose edits would need a second endpoint.
- `PUT /{id}` (full replace): needs fields the patient may not change, and avoids the gateway CORS edit below but misstates the semantics.
- Cancel + rebook: loses the appointment id, loses it atomically (slot could be lost between calls) and emits two events.
- Hard-coded 48h: simpler, but the user chose a property.

## Fits the codebase
- Validation reuses the `List<BookingRule>` and the local `ValidDoctor` read model (ADR-005); no call to doctor-service.
- Concurrency reuses the partial unique index `ux_active_appointment (doctor_id, date_time) WHERE status <> 'CANCELLED'` and the `saveAndFlush` + catch pattern from `bookAppointment`.
- Time comes from the injected `Clock`, as for `cancelledAt` and past-slot hiding.
- Errors: new exceptions plus handlers in `GlobalExceptionHandler` producing RFC 9457 `ProblemDetail`, like the existing ones. `AppointmentAccessDeniedException`'s message is cancel-specific; it is reworded to be generic.
- The past-time check is the existing `NotInPastRule` (added to main for booking); change reuses it, nothing new.
- Config: `@ConfigurationProperties`-style/`@Value` property in `application.yaml`, default 48.
- No new Kafka event (see below), so no outbox use.

## Hard-to-reverse decisions
- API shape: PATCH with partial body and the two 409 titles. ADR planned: no (an endpoint, not an architectural decision).
- No event for a changed appointment: the `appointment.booked` / `appointment.cancelled` consumers (notification-service) won't learn of the change, so a patient gets no notification and the old slot's `AppointmentBooked` stays the last word. Adding `appointment.changed` later is additive. ADR planned: no.

## Contracts
- New: `PATCH /api/appointments/{id}`; request `AppointmentUpdateRequest {type?, dateTime?, notes? (<=500)}`; responses 200 `AppointmentResponse`, 400 (empty body, validation, unaligned, outside hours, past), 403 (not owner / non-patient token), 404, 409 (slot taken, cancelled, within notice window).
- Gateway: `api-gateway` `SecurityConfig` CORS `allowedMethods` lacks PATCH, so a browser preflight for PATCH is rejected. PATCH is added to the list. Routing (`/api/appointments/**`) and authentication are unchanged.
- Config: new property `appointment.change.min-notice-hours` in appointment-service `application.yaml` (and `docker-compose.yml` is not touched).
- Schema: none (no column, same index). Events: none.

## Failure modes
- A change racing a cancel (or another change) on the same appointment: `Appointment` has no `@Version`, so a plain load-then-save could write back `CONFIRMED` over a committed cancel. Both `updateAppointment` and `cancelAppointment` therefore load the row with a new repository method `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), so the second request waits, then sees the committed status (a change after a cancel gets 409). No schema change. `cancelAppointment` is touched only for this lookup; its behavior is otherwise unchanged.
- Two patients move to the same slot concurrently: the unique index rejects one -> 409; the transaction rolls back and the old time is kept.
- A patient moves onto a slot freed by a cancellation: allowed, the index is partial.
- Changing to the appointment's own current time: treated as unchanged (no index conflict, no validation failure).
- doctor/patient missing in read model: existing 404 `Doctor Not Found` for time changes; type/notes-only changes need no doctor lookup.
- Clock-boundary: the notice check uses the injected `Clock`; `NotInPastRule` reads it separately, so a request at the exact boundary instant is not a case worth pinning beyond the two sides tested.
- Kafka down: not involved (no event published).

## Test plan
- `AppointmentServiceTest` (fixed `Clock`): type only, notes only, time only, both; same time as current; exactly 48h allowed vs 48h minus a minute rejected; new time at now (rejected) and one slot after (accepted); unaligned; slot starting at opening time and slot ending at closing time accepted, one slot before/after rejected; slot taken; cancelled; not owner; appointment not found; doctor missing from the read model on a time change (404 `Doctor Not Found`); empty `notes` clears notes; each failure leaves the row unsaved. Cancel's existing unit tests are adapted to `findByIdForUpdate`.
- `AppointmentControllerTest`: passes the token subject; propagates exceptions. `AppointmentSecurityTest` (`@WebMvcTest`): DOCTOR token -> 403; empty body -> 400; notes of 500 accepted, 501 -> 400.
- `AppointmentIntegrationTest` (Testcontainers): change moves the booking and frees the old slot; concurrent changes to one slot -> one 200, rest 409; concurrent change and cancel on one appointment -> the final state is CANCELLED whichever runs first (change gets 409 or 200 before the cancel, never a revived appointment); ProblemDetail titles for 403/404/409; no token 401.
- `OpenApiDocsTest`: PATCH responses documented with problem bodies.
- Gateway: `GatewaySecurityTest` or a CORS preflight test for PATCH.
- Scripts: `test-flow.sh` and `gateway-security-smoke-test.sh` get a change step. Frontend has no test runner; checked by hand.

## Ask first
- API contract: new `PATCH /api/appointments/{id}` and its request/response/status codes.
- Security: add `PATCH` to the gateway CORS `allowedMethods` (`api-gateway/.../config/SecurityConfig.java`).
- Config: new `application.yaml` property in appointment-service.
- Decision: no `appointment.changed` event in this change (so no notification of changes).
- No CLAUDE.md rule or ADR is departed from.
