#!/usr/bin/env bash
#
# gateway-security-smoke-test.sh - pre-merge gate for the gateway edge-validation
# change (ADR-004 correction). Exercises the gateway (8080) against a running stack
# and asserts the exact behaviors the change is meant to produce:
#
#   - a public path (register/login) is not blocked at the edge
#   - a protected path with no token is rejected (401) - the new behavior
#   - a protected path with a valid token succeeds (200)
#   - a protected path with a wrong-role (DOCTOR) token is rejected (403) end to end
#   - an OPTIONS preflight on a public POST route is not blocked (401/403)
#
# Requires: curl, jq. Services and infra (Kafka, Postgres) must be running
# (docker-compose up -d), same precondition as scripts/test-flow.sh.

set -euo pipefail

GATEWAY="http://localhost:8080"

section() { printf '\n\033[1;34m=== %s ===\033[0m\n' "$1"; }
ok()      { printf '\033[0;32mOK: %s\033[0m\n' "$1"; }
fail()    { printf '\033[0;31mFAIL: %s\033[0m\n' "$1"; exit 1; }

assert_not_status() {
  local desc="$1" bad_status="$2" actual="$3"
  [ "$actual" != "$bad_status" ] && ok "$desc ($actual)" || fail "$desc returned $actual (must not be $bad_status)"
}

assert_status() {
  local desc="$1" expected="$2" actual="$3"
  [ "$actual" = "$expected" ] && ok "$desc ($actual)" || fail "$desc returned $actual (expected $expected)"
}

section "Register patient (public path must not be blocked at the edge)"
PATIENT_RESP="$(curl -s -X POST "$GATEWAY/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Gate", "lastName": "Check",
    "username": "gate.check.'"$RANDOM"'", "password": "secret",
    "dateOfBirth": "1990-01-01",
    "email": "gate.check.'"$RANDOM"'@example.com",
    "insuranceType": "PRIVATE"
  }')"
PATIENT_TOKEN="$(echo "$PATIENT_RESP" | jq -r '.token')"
[ "$PATIENT_TOKEN" != "null" ] && [ -n "$PATIENT_TOKEN" ] || fail "register did not return a token: $PATIENT_RESP"
ok "patient registered at the edge (public path passed)"

section "Register + login doctor (for the wrong-role case below)"
SPECIALTY_ID="$(curl -s "$GATEWAY/api/specialties" | jq -r '.[0].id')"
[ "$SPECIALTY_ID" != "null" ] && [ -n "$SPECIALTY_ID" ] || fail "no specialty found; is the seeder running?"
DOCTOR_EMAIL="gate.doctor.$RANDOM@clinic.com"
DOCTOR_RESP="$(curl -s -X POST "$GATEWAY/api/doctors/register" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Gate", "lastName": "Doctor",
    "email": "'"$DOCTOR_EMAIL"'",
    "password": "secret123",
    "phoneNumber": "+49 30 1234567",
    "address": { "street": "Friedrichstrasse", "houseNumber": "200", "postalCode": "10117", "city": "Berlin", "country": "Germany" },
    "specialtyIds": ["'"$SPECIALTY_ID"'"],
    "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" } ],
    "languages": ["ENGLISH"]
  }')"
DOCTOR_ID="$(echo "$DOCTOR_RESP" | jq -r '.id')"
[ "$DOCTOR_ID" != "null" ] && [ -n "$DOCTOR_ID" ] || fail "doctor register failed: $DOCTOR_RESP"

section "Login (200, capture token)"
LOGIN_HTTP="$(curl -s -o /tmp/gate_login_resp.json -w '%{http_code}' -X POST "$GATEWAY/api/doctors/login" \
  -H "Content-Type: application/json" \
  -d '{ "email": "'"$DOCTOR_EMAIL"'", "password": "secret123" }')"
assert_status "doctor login" 200 "$LOGIN_HTTP"
DOCTOR_TOKEN="$(jq -r '.token' /tmp/gate_login_resp.json)"
[ "$DOCTOR_TOKEN" != "null" ] && [ -n "$DOCTOR_TOKEN" ] || fail "no token in login response"

section "GET /api/appointments with a valid PATIENT token: 200"
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/appointments" \
  -H "Authorization: Bearer $PATIENT_TOKEN")"
assert_status "GET /api/appointments with token" 200 "$CODE"

section "GET /api/appointments with no token: 401 (the new edge-rejection behavior)"
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/appointments")"
assert_status "GET /api/appointments with no token" 401 "$CODE"

section "GET /api/appointments with a DOCTOR token: 403 (role enforcement through the gateway)"
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/appointments" \
  -H "Authorization: Bearer $DOCTOR_TOKEN")"
assert_status "GET /api/appointments with DOCTOR token" 403 "$CODE"

section "OPTIONS preflight on public POST routes: not blocked"
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS "$GATEWAY/api/auth/register" \
  -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST")"
assert_not_status "OPTIONS /api/auth/register" 401 "$CODE"
[ "$CODE" != "403" ] || fail "OPTIONS /api/auth/register returned 403"

CODE="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS "$GATEWAY/api/auth/login" \
  -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST")"
assert_not_status "OPTIONS /api/auth/login" 401 "$CODE"
[ "$CODE" != "403" ] || fail "OPTIONS /api/auth/login returned 403"

section "Done"
ok "gateway security smoke test passed"
