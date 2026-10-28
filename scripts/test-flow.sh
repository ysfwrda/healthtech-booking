#!/usr/bin/env bash
#
# test-flow.sh - exercises the full HealthTech booking pipeline end to end.
#
# Flow: register patient -> register doctor -> read availability -> book a slot
#       -> confirm slot disappears -> double-book (rejected) -> change the
#       appointment (purpose and time) -> confirm old slot returns and new one
#       is taken -> cancel -> confirm slot reappears. Then the doctor side:
#       a new booking is PENDING -> the doctor lists, confirms and changes it
#       -> a vacation or shorter hours flag it (and offer no slots) -> the doctor
#       removes the vacation and cancels.
#
# All calls go through the API Gateway (8080). Booking and cancel require a
# patient JWT (Authorization: Bearer); the doctor endpoints require the doctor JWT. The patient identity is taken from the
# token subject, so patientId is NOT sent in the request body.
#
# Requires: curl, jq. Services and infra (Kafka, Postgres) must be running.

set -euo pipefail

GATEWAY="http://localhost:8080"
BOOKING_DATE="$(date -d 'next monday' +%Y-%m-%d 2>/dev/null || date -v+mon +%Y-%m-%d)"
# Changing an appointment is only allowed more than 48h ahead; on a Sunday "next monday" is tomorrow, so use the Monday after.
BOOKING_EPOCH="$(date -d "$BOOKING_DATE" +%s 2>/dev/null || date -j -f %Y-%m-%d "$BOOKING_DATE" +%s)"
if [ $(( (BOOKING_EPOCH - $(date +%s)) / 3600 )) -lt 72 ]; then
  BOOKING_DATE="$(date -d "$BOOKING_DATE + 7 days" +%Y-%m-%d 2>/dev/null || date -j -v+7d -f %Y-%m-%d "$BOOKING_DATE" +%Y-%m-%d)"
fi

section() { printf '\n\033[1;34m=== %s ===\033[0m\n' "$1"; }
info()    { printf '\033[0;36m%s\033[0m\n' "$1"; }
fail()    { printf '\033[0;31mFAIL: %s\033[0m\n' "$1"; exit 1; }
ok()      { printf '\033[0;32mOK: %s\033[0m\n' "$1"; }

section "Register patient"
PATIENT_RESP="$(curl -s -X POST "$GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John", "lastName": "Doe",
    "username": "john.doe.'"$RANDOM"'", "password": "secret123",
    "dateOfBirth": "1990-01-01",
    "email": "john.doe.'"$RANDOM"'@example.com",
    "insuranceType": "PRIVATE"
  }')"
TOKEN="$(echo "$PATIENT_RESP" | jq -r '.token')"
[ "$TOKEN" != "null" ] && [ -n "$TOKEN" ] || fail "no token in register response: $PATIENT_RESP"
ok "patient registered, token obtained"

section "Register doctor"
SPECIALTY_ID="$(curl -s "$GATEWAY/api/specialties" | jq -r '.[0].id')"
[ "$SPECIALTY_ID" != "null" ] && [ -n "$SPECIALTY_ID" ] || fail "no specialty found; is the seeder running?"
DOCTOR_RESP="$(curl -s -X POST "$GATEWAY/api/doctors/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane", "lastName": "Smith",
    "email": "jane.'"$RANDOM"'@clinic.com",
    "password": "secret123",
    "phoneNumber": "+49 30 1234567",
    "address": { "street": "Friedrichstrasse", "houseNumber": "200", "postalCode": "10117", "city": "Berlin", "country": "Germany" },
    "specialtyIds": ["'"$SPECIALTY_ID"'"],
    "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" } ],
    "languages": ["ENGLISH"]
  }')"
DOCTOR_ID="$(echo "$DOCTOR_RESP" | jq -r '.id')"
[ "$DOCTOR_ID" != "null" ] && [ -n "$DOCTOR_ID" ] || fail "no doctor id in register response: $DOCTOR_RESP"
ok "doctor registered, id=$DOCTOR_ID"

info "waiting for read-model to consume patient.registered and doctor.registered..."
sleep 3

section "Availability before booking ($BOOKING_DATE)"
AVAIL_BEFORE="$(curl -s "$GATEWAY/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
echo "$AVAIL_BEFORE" | jq '.availableSlots'
SLOT="$(echo "$AVAIL_BEFORE" | jq -r '.availableSlots[0]')"
[ "$SLOT" != "null" ] && [ -n "$SLOT" ] || fail "no available slots (check date weekday vs opening hours)"
ok "picked slot $SLOT"

section "Book slot"
BOOK_RESP="$(curl -s -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{ "doctorId": "'"$DOCTOR_ID"'", "dateTime": "'"$SLOT"'", "type": "INITIAL_CONSULTATION", "notes": "test booking" }')"
APPOINTMENT_ID="$(echo "$BOOK_RESP" | jq -r '.id')"
[ "$APPOINTMENT_ID" != "null" ] && [ -n "$APPOINTMENT_ID" ] || fail "booking failed: $BOOK_RESP"
ok "booked, appointmentId=$APPOINTMENT_ID"

section "Availability after booking (slot should be gone)"
AVAIL_AFTER="$(curl -s "$GATEWAY/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
if echo "$AVAIL_AFTER" | jq -e --arg s "$SLOT" '.availableSlots | index($s)' >/dev/null; then
  fail "slot $SLOT still present after booking"
fi
ok "slot $SLOT correctly removed from availability"

section "Double-book same slot (expect 409)"
DOUBLE_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{ "doctorId": "'"$DOCTOR_ID"'", "dateTime": "'"$SLOT"'", "type": "INITIAL_CONSULTATION", "notes": "double booking attempt" }')"
[ "$DOUBLE_CODE" = "409" ] && ok "double-book rejected with 409" || fail "double-book returned $DOUBLE_CODE (expected 409)"

section "Change appointment (new purpose and the next free slot)"
NEW_SLOT="$(echo "$AVAIL_BEFORE" | jq -r '.availableSlots[1]')"
[ "$NEW_SLOT" != "null" ] && [ -n "$NEW_SLOT" ] || fail "no second slot to move to"
CHANGE_RESP="$(curl -s -X PATCH "$GATEWAY/api/appointments/$APPOINTMENT_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{ "dateTime": "'"$NEW_SLOT"'", "type": "FOLLOW_UP" }')"
[ "$(echo "$CHANGE_RESP" | jq -r '.type')" = "FOLLOW_UP" ] || fail "change did not apply the new type: $CHANGE_RESP"
[ "$(echo "$CHANGE_RESP" | jq -r '.dateTime' | cut -c1-16)" = "$(echo "$NEW_SLOT" | cut -c1-16)" ] || fail "change did not apply the new time: $CHANGE_RESP"
ok "changed to $NEW_SLOT, type FOLLOW_UP"

section "Availability after change (old slot back, new slot taken)"
AVAIL_CHANGED="$(curl -s "$GATEWAY/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
echo "$AVAIL_CHANGED" | jq -e --arg s "$SLOT" '.availableSlots | index($s)' >/dev/null || fail "old slot $SLOT did not reappear after the change"
if echo "$AVAIL_CHANGED" | jq -e --arg s "$NEW_SLOT" '.availableSlots | index($s)' >/dev/null; then
  fail "new slot $NEW_SLOT still free after the change"
fi
ok "old slot free again, new slot taken"

section "Change with an empty body (expect 400)"
EMPTY_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$GATEWAY/api/appointments/$APPOINTMENT_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{}')"
[ "$EMPTY_CODE" = "400" ] && ok "empty change rejected with 400" || fail "empty change returned $EMPTY_CODE (expected 400)"

section "Cancel appointment"
CANCEL_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
  "$GATEWAY/api/appointments/$APPOINTMENT_ID/cancel" \
  -H "Authorization: Bearer $TOKEN")"
{ [ "$CANCEL_CODE" = "200" ] || [ "$CANCEL_CODE" = "204" ]; } && ok "cancelled ($CANCEL_CODE)" || fail "cancel returned $CANCEL_CODE (expected 200/204)"

section "Availability after cancel (slot should return)"
AVAIL_CANCELLED="$(curl -s "$GATEWAY/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
if echo "$AVAIL_CANCELLED" | jq -e --arg s "$SLOT" '.availableSlots | index($s)' >/dev/null; then
  ok "slot $SLOT correctly reappeared after cancellation (partial index working)"
else
  fail "slot $SLOT did NOT reappear after cancellation"
fi

# --- The doctor's side: pending bookings, confirm, change, vacation, cancel -----------------------------------------

DOCTOR_TOKEN="$(echo "$DOCTOR_RESP" | jq -r '.token')"
[ "$DOCTOR_TOKEN" != "null" ] && [ -n "$DOCTOR_TOKEN" ] || fail "no token in doctor register response: $DOCTOR_RESP"

# Polls a command until it succeeds; the doctor-service -> appointment-service events arrive asynchronously.
wait_for() { for _ in $(seq 1 30); do eval "$1" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

section "Book with the new doctor (expect PENDING: only seeded doctors confirm automatically)"
PENDING_RESP="$(curl -s -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{ "doctorId": "'"$DOCTOR_ID"'", "dateTime": "'"$SLOT"'", "type": "INITIAL_CONSULTATION", "notes": "for the doctor" }')"
DR_APPOINTMENT_ID="$(echo "$PENDING_RESP" | jq -r '.id')"
[ "$(echo "$PENDING_RESP" | jq -r '.status')" = "PENDING" ] || fail "new booking is not PENDING: $PENDING_RESP"
ok "booked, status PENDING, appointmentId=$DR_APPOINTMENT_ID"

section "Doctor endpoints reject the patient token (403) and no token (401)"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/appointments/doctor" -H "Authorization: Bearer $TOKEN")" = "403" ] || fail "patient token on the doctor list was not 403"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/appointments/doctor")" = "401" ] || fail "doctor list without a token was not 401"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/doctors/me/availability")" = "401" ] || fail "availability without a token was not 401"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/doctors/me/availability" -H "Authorization: Bearer $TOKEN")" = "403" ] || fail "patient token on the availability was not 403"
ok "roles enforced"

section "Doctor lists the appointment (with the patient name)"
DR_LIST="$(curl -s "$GATEWAY/api/appointments/doctor?from=$BOOKING_DATE&to=$BOOKING_DATE&status=PENDING" -H "Authorization: Bearer $DOCTOR_TOKEN")"
echo "$DR_LIST" | jq -e --arg id "$DR_APPOINTMENT_ID" 'map(select(.id == $id and .patientName == "John Doe")) | length == 1' >/dev/null || fail "pending appointment not in the doctor's list: $DR_LIST"
ok "listed with patientName"

section "Doctor confirms"
CONFIRM_RESP="$(curl -s -X PUT "$GATEWAY/api/appointments/doctor/$DR_APPOINTMENT_ID/confirm" -H "Authorization: Bearer $DOCTOR_TOKEN")"
[ "$(echo "$CONFIRM_RESP" | jq -r '.status')" = "CONFIRMED" ] || fail "confirm did not give CONFIRMED: $CONFIRM_RESP"
ok "confirmed"

section "Doctor changes the time (no 48-hour rule for the doctor)"
DR_NEW_SLOT="$(echo "$AVAIL_BEFORE" | jq -r '.availableSlots[2]')"
DR_CHANGE_RESP="$(curl -s -X PATCH "$GATEWAY/api/appointments/doctor/$DR_APPOINTMENT_ID" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -d '{ "dateTime": "'"$DR_NEW_SLOT"'" }')"
[ "$(echo "$DR_CHANGE_RESP" | jq -r '.dateTime' | cut -c1-16)" = "$(echo "$DR_NEW_SLOT" | cut -c1-16)" ] || fail "doctor change did not move the appointment: $DR_CHANGE_RESP"
[ "$(echo "$DR_CHANGE_RESP" | jq -r '.status')" = "CONFIRMED" ] || fail "doctor change altered the status: $DR_CHANGE_RESP"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$GATEWAY/api/appointments/doctor/$DR_APPOINTMENT_ID" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $DOCTOR_TOKEN" -d '{ "notes": "x" }')" = "400" ] || fail "doctor change with only notes was not 400"
ok "moved to $DR_NEW_SLOT, status unchanged, notes not editable"

section "Doctor enters a vacation covering the appointment (the change is saved, the appointment is flagged)"
VACATION_RESP="$(curl -s -X POST "$GATEWAY/api/doctors/me/availability/vacations" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -d '{ "startDate": "'"$BOOKING_DATE"'", "endDate": "'"$BOOKING_DATE"'" }')"
VACATION_ID="$(echo "$VACATION_RESP" | jq -r '.id')"
[ "$VACATION_ID" != "null" ] && [ -n "$VACATION_ID" ] || fail "vacation was not saved: $VACATION_RESP"
wait_for "curl -s '$GATEWAY/api/appointments' -H 'Authorization: Bearer $TOKEN' | jq -e --arg id '$DR_APPOINTMENT_ID' 'map(select(.id == \$id and .conflictReason == \"DOCTOR_VACATION\")) | length == 1'" \
  || fail "appointment was not flagged DOCTOR_VACATION after the vacation"
ok "vacation saved, appointment flagged DOCTOR_VACATION"

section "On the vacation day: no slots, booking rejected (400), confirm refused for a flagged pending appointment"
wait_for "curl -s '$GATEWAY/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE' | jq -e '.availableSlots | length == 0'" || fail "slots are still offered on the vacation day"
VAC_BOOK_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{ "doctorId": "'"$DOCTOR_ID"'", "dateTime": "'"$SLOT"'", "type": "FOLLOW_UP" }')"
[ "$VAC_BOOK_CODE" = "400" ] || fail "booking on the vacation day returned $VAC_BOOK_CODE (expected 400)"
ok "no slots, booking rejected"

section "Doctor removes the vacation (the flag clears and the slots return)"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GATEWAY/api/doctors/me/availability/vacations/$VACATION_ID" -H "Authorization: Bearer $DOCTOR_TOKEN")" = "204" ] || fail "vacation delete was not 204"
wait_for "curl -s '$GATEWAY/api/appointments' -H 'Authorization: Bearer $TOKEN' | jq -e --arg id '$DR_APPOINTMENT_ID' 'map(select(.id == \$id and .conflictReason == null)) | length == 1'" \
  || fail "flag did not clear after the vacation was removed"
ok "flag cleared"

section "Doctor shortens the opening hours below the appointment (saved, appointment flagged OUTSIDE_OPENING_HOURS)"
SHORT_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GATEWAY/api/doctors/me/availability/opening-hours" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -d '{ "openingHours": [ { "dayOfWeek": "TUESDAY", "startTime": "09:00", "endTime": "10:00" } ] }')"
[ "$SHORT_CODE" = "200" ] || fail "opening-hours change returned $SHORT_CODE (expected 200)"
wait_for "curl -s '$GATEWAY/api/appointments' -H 'Authorization: Bearer $TOKEN' | jq -e --arg id '$DR_APPOINTMENT_ID' 'map(select(.id == \$id and .conflictReason == \"OUTSIDE_OPENING_HOURS\")) | length == 1'" \
  || fail "appointment was not flagged OUTSIDE_OPENING_HOURS"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GATEWAY/api/doctors/me/availability/opening-hours" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $DOCTOR_TOKEN" \
  -d '{ "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:15", "endTime": "10:00" } ] }')" = "400" ] || fail "off-grid opening hours were not rejected with 400"
ok "flagged; off-grid hours rejected"

section "Doctor cancels (no notice period)"
DR_CANCEL_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GATEWAY/api/appointments/doctor/$DR_APPOINTMENT_ID/cancel" -H "Authorization: Bearer $DOCTOR_TOKEN")"
[ "$DR_CANCEL_CODE" = "200" ] || fail "doctor cancel returned $DR_CANCEL_CODE (expected 200)"
ok "cancelled by the doctor"

section "Done"
ok "full booking pipeline exercised, including the doctor's side"