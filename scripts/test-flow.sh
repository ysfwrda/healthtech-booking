#!/usr/bin/env bash
#
# test-flow.sh - exercises the full HealthTech booking pipeline end to end.
#
# Flow: register patient -> register doctor -> read availability -> book a slot
#       -> confirm slot disappears -> double-book (rejected) -> cancel
#       -> confirm slot reappears.
#
# All calls go through the API Gateway (8080). Booking and cancel require a
# patient JWT (Authorization: Bearer). The patient identity is taken from the
# token subject, so patientId is NOT sent in the request body.
#
# Requires: curl, jq. Services and infra (Kafka, Postgres) must be running.

set -euo pipefail

GATEWAY="http://localhost:8080"
BOOKING_DATE="$(date -d 'next monday' +%Y-%m-%d 2>/dev/null || date -v+mon +%Y-%m-%d)"

section() { printf '\n\033[1;34m=== %s ===\033[0m\n' "$1"; }
info()    { printf '\033[0;36m%s\033[0m\n' "$1"; }
fail()    { printf '\033[0;31mFAIL: %s\033[0m\n' "$1"; exit 1; }
ok()      { printf '\033[0;32mOK: %s\033[0m\n' "$1"; }

section "Register patient"
PATIENT_RESP="$(curl -s -X POST "$GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John", "lastName": "Doe",
    "username": "john.doe.'"$RANDOM"'", "password": "secret",
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
DOCTOR_RESP="$(curl -s -X POST "$GATEWAY/api/doctors" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "firstName": "Jane", "lastName": "Smith",
    "email": "jane.'"$RANDOM"'@clinic.com",
    "phoneNumber": "+49 30 1234567",
    "address": { "street": "Friedrichstrasse", "houseNumber": "200", "postalCode": "10117", "city": "Berlin", "country": "Germany" },
    "specialtyIds": ["'"$SPECIALTY_ID"'"],
    "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" } ],
    "languages": ["ENGLISH"]
  }')"
DOCTOR_ID="$(echo "$DOCTOR_RESP" | jq -r '.id')"
[ "$DOCTOR_ID" != "null" ] && [ -n "$DOCTOR_ID" ] || fail "no doctor id in response: $DOCTOR_RESP"
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

section "Done"
ok "full booking pipeline exercised"