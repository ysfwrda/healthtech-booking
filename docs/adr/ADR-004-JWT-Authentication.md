# ADR-004: JWT Authentication

## Status

Accepted (amended 2026-08-09 - see Correction below)

## Context

With Patient Service and Doctor Service being introduced in Phase 2, the system
needs a mechanism to authenticate users and authorize access to protected
resources across services.

Three concerns must be addressed: where tokens are issued, where they are
validated, and what information they carry. A naive approach of issuing and
validating tokens in every service would introduce duplication and inconsistency.
Equally, relying solely on the API Gateway for validation would leave internal
service-to-service communication unprotected in the event of a misconfigured or
compromised internal caller.

Two signing algorithms were evaluated: HS256 (symmetric, shared secret) and
RS256 (asymmetric, private and public key pair).

HS256 requires every validating party to hold the shared secret, meaning any
service capable of validating a token is also capable of forging one. RS256
separates signing from verification: only Patient Service holds the private key
and can issue tokens, while the public key can be distributed freely to any
service that needs to validate without granting signing capability.

## Decision

Domain services issue JWT tokens for their own user type, signed with RS256. Patient
Service issues PATIENT tokens; Doctor Service issues DOCTOR tokens. Token issuance is
therefore decentralized across domain services rather than centralized in one issuer.
All issuers share a single RS256 key pair: the private key is held by each issuing
service for signing, and the public key is distributed to all validating services,
including the API Gateway.

Because a single shared key pair is used, a token's signature does not identify which
user type it represents: any issuer's token verifies against the same public key.
Validators therefore distinguish token types by the `role` claim, not by the key or the
signature. A service authorizing a patient action reads `role` to confirm the token is a
PATIENT token before treating `sub` as a patient identity, and rejects tokens of the
wrong type.

Token validation follows a hybrid model with a strict division of responsibility
between its two layers:

* **The API Gateway performs edge validation only: signature and expiry.** It decodes
  every inbound request against the shared public key and rejects anything invalid or
  expired before it reaches a downstream service. It does **not** inspect the `role`
  claim and makes no authorization decision - a validly signed, unexpired token of
  either type passes the gateway. This keeps the gateway's job narrow (authenticity,
  not authorization) and means it never needs to know which roles a given route
  requires.
* **Each domain service (Patient, Doctor, Appointment) is the authoritative boundary
  for role authorization**, and validates independently of the gateway, enforcing a
  zero-trust posture: internal traffic is verified per service, so a misconfigured or
  compromised internal caller cannot bypass authorization by reaching a service
  directly.

The two layers are complementary: the gateway provides earlier, edge-level rejection
of unauthenticated traffic; per-service validation remains authoritative and is what
actually enforces who may do what.

Role authorization inside each service is enforced declaratively, not procedurally.
A `JwtAuthenticationConverter` maps the `role` claim onto a Spring Security
`ROLE_<role>` authority, and each service's `SecurityConfig` requires that authority
per route (`hasRole("PATIENT")` in Patient Service and Appointment Service,
`hasRole("DOCTOR")` in Doctor Service) alongside the existing public-path permits.
Enforcement therefore lives in one place per service - the security filter chain - not
in `@PreAuthorize` annotations scattered across controllers, and not in manual
`if (!"PATIENT".equals(jwt.getClaimAsString("role")))` checks inside controller
methods. This makes the rule visible in one file, testable against the real
authorization rules with a mocked `JwtDecoder` (no key material needed), and
impossible for a new controller method to accidentally skip.

The gateway's own permitted-path list is the **exact union** of the three services'
own public paths (specific method-and-path matchers, not a wildcard pattern like
`/**/register`). This is the least-privilege choice: a newly added public endpoint
requires an explicit, reviewed addition to the gateway list, and a companion gateway
test (asserting every known public path is not rejected with 401/403, and that a
protected path with no token is) fails immediately if that list drifts from what the
services actually expose. The trade-off is weighed consciously against a
pattern-based alternative that fails safer on a forgotten endpoint (new routes
default to protected) at the cost of looser matching; either is compatible with this
ADR, but the exact-union list is what is implemented.

Tokens carry three claims: `sub`, `role`, and `exp`.

* `sub` is the subject's identifier: the patient id in a PATIENT token, the doctor id in
  a DOCTOR token. Downstream services derive the acting user's identity from `sub` rather
  than from request bodies, so a caller cannot act on behalf of another user by supplying
  a different id in the payload.
* `role` identifies the user type (PATIENT or DOCTOR) and is what validators use to
  distinguish token types under the shared key.
* `exp` sets expiry. Access tokens are valid for one hour.

The claim set is intentionally minimal: services such as Appointment Service need only
the acting user's identity and role to authorize a request, not profile data.

A client-side inactivity timeout of 15 minutes is applied for additional security,
covering scenarios such as account theft or fraud where a shorter effective session
window reduces exposure. Refresh tokens are not implemented in this phase; they are
documented here as a known future improvement.

## Correction (2026-08-09)

The initial implementation of this ADR diverged from the decision above in two ways
that this correction fixes:

1. **The gateway performed no validation at all.** Its `SecurityFilterChain` was
   `.anyRequest().permitAll()` - every request, including `POST /api/appointments`
   with no `Authorization` header, was routed straight to a downstream service. The
   "edge-level rejection of unauthenticated traffic" described above did not exist in
   practice; only per-service validation was actually enforcing anything, and only for
   the services a caller happened to reach. The gateway now holds the public key,
   decodes and validates every request via `spring-boot-starter-oauth2-resource-server`,
   and rejects unauthenticated or expired traffic on any path outside the exact public
   union described above.
2. **Role enforcement was inconsistent across services, and, where present, was not
   in the security layer.** Patient Service and Doctor Service used
   `.anyRequest().authenticated()`, which accepts any validly signed token regardless
   of role - a DOCTOR token could pass Patient Service's authentication step (though
   business-level ownership checks limited what it could then do). Appointment Service
   did enforce role, but did so with a manual check
   (`if (!"PATIENT".equals(jwt.getClaimAsString("role"))) throw new
   WrongTokenTypeException();`) duplicated across three controller methods, one
   accidental omission away from silently accepting a DOCTOR token. All three services
   now use the same `JwtAuthenticationConverter` + `hasRole(...)` pattern in
   `SecurityConfig` described above; the controller-level check and its exception
   class were removed from Appointment Service as dead code once the security layer
   took over that responsibility, since the HTTP status (403) is unchanged.

Neither issue changed the trust model this ADR sets out - RS256, the shared key pair,
and the role claim as discriminator all still hold - but together they meant the
"hybrid validation" and "role claim as discriminator" decisions were, in part,
aspirational rather than actually enforced. This correction makes the implementation
match the decision, and adds regression coverage (a gateway test asserting the public
path union, and per-service tests asserting a wrong-role token is rejected with 403)
so a future change that reopens either gap fails a test rather than shipping silently.

This also reverses an earlier deployment note that the gateway did not need the
shared key mounted: it now needs the **public key only** (never the private key) to
validate tokens. The local `docker-compose.yml` already mounts `keys/public.pem`
into the gateway and sets `JWT_PUBLIC_KEY_PATH` accordingly. This repository does not
yet have a separate AWS/production compose file or an ADR-008; if one is introduced,
it must mount the public key into the gateway the same way, and must never mount the
private key there.

## Consequences

### Positive

* RS256 asymmetry means signing capability is never distributed to validators: services
  can verify tokens without being able to forge them. Only services holding the private
  key can issue tokens.
* Deriving identity from the validated `sub` claim, rather than from request bodies,
  closes a class of impersonation: a caller cannot book or act as another user by
  supplying that user's id in the payload.
* The hybrid validation model provides defense in depth: external traffic is filtered at
  the gateway, and internal traffic is independently verified per service, so per-service
  validation remains authoritative even if a request bypasses the gateway.
* Declarative role enforcement (`JwtAuthenticationConverter` + `hasRole(...)` in
  `SecurityConfig`) keeps the authorization rule in one testable place per service,
  rather than spread across controller methods or `@PreAuthorize` annotations that are
  easy to miss on a new endpoint.
* Minimal claims keep the token small and avoid leaking patient profile data to services
  that do not need it.

### Negative

* Per-service validation requires distributing the public key and configuring Spring
  Security in each validating service, adding setup overhead. The gateway is now one
  more place that needs it.
* The gateway's exact-union public-path list is a manually maintained artifact: it must
  be updated whenever a service adds or removes a public path, or the gateway will wrongly
  block (or wrongly expose) that path at the edge. The companion gateway test bounds this
  risk but does not eliminate the manual step.
* Without refresh tokens, a user whose session approaches the one-hour expiry must
  re-authenticate; this must be addressed before any production use.
* The 15-minute inactivity timeout is client-enforced and therefore not a server-side
  security guarantee; a determined client could ignore it.
* Issuance is decentralized: more than one domain service holds the shared private key
  and can mint tokens. This is a pragmatic tradeoff at the current scale, but it means
  there is no single issuer as a trust boundary, and a compromise of the private key on
  any issuing service affects the whole system. With more issuing services, a need for
  independent key rotation, or a need to re-centralize the trust boundary, extracting a
  dedicated Identity Service (the single issuer, holding the private key exclusively)
  becomes the appropriate evolution. This is documented as a known future improvement.
