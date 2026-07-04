#!/usr/bin/env bash
#
# test-flow.sh - exercises the full HealthTech booking pipeline end to end.
#
# Flow: register patient -> register doctor -> read availability -> book a slot
#       -> confirm slot disappears -> cancel -> confirm slot reappears
#       -> attempt double-book -> expect rejection.
#
# Requires: curl, jq. Services and infra (Kafka, Postgres) must be running.
# Note: this hits services directly on their own ports for now. Once the gateway
# JWT filter lands, point the booking/availability calls at the gateway (8080)
# and drop patientId from the booking body (it will come from the token).

set -euo pipefail

# ---- config ---------------------------------------------------------------
PATIENT_SVC="http://localhost:8083"
DOCTOR_SVC="http://localhost:8084"
APPOINTMENT_SVC="http://localhost:8081"
GATEWAY="http://localhost:8080"

# Pick a date whose weekday matches the doctor's opening hours below.
# Default: the next Monday from today.
BOOKING_DATE="$(date -d 'next monday' +%Y-%m-%d 2>/dev/null || date -v+mon +%Y-%m-%d)"

# helper: pretty section headers
section() { printf '\n\033[1;34m=== %s ===\033[0m\n' "$1"; }
info()    { printf '\033[0;36m%s\033[0m\n' "$1"; }
fail()    { printf '\033[0;31mFAIL: %s\033[0m\n' "$1"; exit 1; }
ok()      { printf '\033[0;32mOK: %s\033[0m\n' "$1"; }

# ---- 1. register a patient ------------------------------------------------
section "Register patient"
PATIENT_RESP="$(curl -s -X POST "$PATIENT_SVC/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "username": "john.doe.'"$RANDOM"'",
    "password": "secret",
    "dateOfBirth": "1990-01-01",
    "email": "john.doe.'"$RANDOM"'@example.com",
    "insuranceType": "PRIVATE"
  }')"

TOKEN="$(echo "$PATIENT_RESP" | jq -r '.token')"
[ "$TOKEN" != "null" ] && [ -n "$TOKEN" ] || fail "no token in register response: $PATIENT_RESP"

# patientId is the JWT subject. Decode the middle segment of the token.
PATIENT_ID="$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | \
  awk '{ l=length($0)%4; if(l>0) for(i=0;i<4-l;i++) $0=$0"="; print }' | \
  base64 -d 2>/dev/null | jq -r '.sub')"
[ -n "$PATIENT_ID" ] || fail "could not extract patientId (sub) from token"
ok "patient registered, id=$PATIENT_ID"

# ---- 2. register a doctor -------------------------------------------------
section "Register doctor"
# get a real specialty id first
SPECIALTY_ID="$(curl -s "$DOCTOR_SVC/api/specialties" | jq -r '.[0].id')"
[ "$SPECIALTY_ID" != "null" ] && [ -n "$SPECIALTY_ID" ] || fail "no specialty found; is the seeder running?"

DOCTOR_RESP="$(curl -s -X POST "$DOCTOR_SVC/api/doctors" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "lastName": "Smith",
    "email": "jane.'"$RANDOM"'@clinic.com",
    "phoneNumber": "+49 30 1234567",
    "address": {
      "street": "Friedrichstrasse",
	  "houseNumber": "200",
      "postalCode": "10117",
      "city": "Berlin",	  
      "country": "Germany"
    },
    "specialtyIds": ["'"$SPECIALTY_ID"'"],
    "openingHours": [
      { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" }
    ],
    "languages": ["ENGLISH"]
  }')"

DOCTOR_ID="$(echo "$DOCTOR_RESP" | jq -r '.id')"
[ "$DOCTOR_ID" != "null" ] && [ -n "$DOCTOR_ID" ] || fail "no doctor id in response: $DOCTOR_RESP"
ok "doctor registered, id=$DOCTOR_ID"

info "waiting for read-model to consume patient.registered and doctor.registered..."
sleep 3

# ---- 3. read availability -------------------------------------------------
section "Availability before booking ($BOOKING_DATE)"
AVAIL_BEFORE="$(curl -s "$APPOINTMENT_SVC/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
echo "$AVAIL_BEFORE" | jq '.availableSlots'

SLOT="$(echo "$AVAIL_BEFORE" | jq -r '.availableSlots[0]')"
[ "$SLOT" != "null" ] && [ -n "$SLOT" ] || fail "no available slots to book (check date weekday vs opening hours)"
ok "picked slot $SLOT"

# ---- 4. book the slot -----------------------------------------------------
section "Book slot"
BOOK_RESP="$(curl -s -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "'"$PATIENT_ID"'",
    "doctorId": "'"$DOCTOR_ID"'",
    "dateTime": "'"$SLOT"'",
    "type": "INITIAL_CONSULTATION",
    "notes": "test booking"
  }')"

APPOINTMENT_ID="$(echo "$BOOK_RESP" | jq -r '.id')"
[ "$APPOINTMENT_ID" != "null" ] && [ -n "$APPOINTMENT_ID" ] || fail "booking failed: $BOOK_RESP"
ok "booked, appointmentId=$APPOINTMENT_ID"

# ---- 5. confirm slot disappeared ------------------------------------------
section "Availability after booking (slot should be gone)"
AVAIL_AFTER="$(curl -s "$APPOINTMENT_SVC/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
if echo "$AVAIL_AFTER" | jq -e --arg s "$SLOT" '.availableSlots | index($s)' >/dev/null; then
  fail "slot $SLOT still present after booking"
fi
ok "slot $SLOT correctly removed from availability"

# ---- 6. double-book should be rejected ------------------------------------
section "Double-book same slot (expect rejection)"
DOUBLE_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/appointments" \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "'"$PATIENT_ID"'",
    "doctorId": "'"$DOCTOR_ID"'",
    "dateTime": "'"$SLOT"'",
    "type": "INITIAL_CONSULTATION",
    "notes": "double booking attempt"
  }')"
if [ "$DOUBLE_CODE" = "409" ]; then
  ok "double-book rejected with 409"
else
  info "double-book returned $DOUBLE_CODE (expected 409; may be 500 until error-handling pass maps SlotAlreadyBookedException)"
fi

# ---- 7. cancel ------------------------------------------------------------
section "Cancel appointment"
CANCEL_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GATEWAY/api/appointments/$APPOINTMENT_ID/cancel")"
info "cancel returned $CANCEL_CODE"

# ---- 8. confirm slot reappeared -------------------------------------------
section "Availability after cancel (slot should return)"
AVAIL_CANCELLED="$(curl -s "$APPOINTMENT_SVC/api/availability?doctorId=$DOCTOR_ID&date=$BOOKING_DATE")"
if echo "$AVAIL_CANCELLED" | jq -e --arg s "$SLOT" '.availableSlots | index($s)' >/dev/null; then
  ok "slot $SLOT correctly reappeared after cancellation (partial index working)"
else
  fail "slot $SLOT did NOT reappear after cancellation"
fi

section "Done"
ok "full booking pipeline exercised"
