# ADR-004: JWT Authentication

## Status

Accepted

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

## Decision

Domain services issue JWT tokens for their own user type, signed with RS256. Patient
Service issues PATIENT tokens; Doctor Service will issue DOCTOR tokens when doctor
authentication is introduced. Token issuance is therefore decentralized across domain
services rather than centralized in one issuer. All issuers share a single RS256 key
pair: the private key is held by each issuing service for signing, and the public key
is distributed to all services for validation.

Because a single shared key pair is used, a token's signature does not identify which
user type it represents: any issuer's token verifies against the same public key.
Validators therefore distinguish token types by the `role` claim, not by the key or the
signature. A service authorizing a patient action reads `role` to confirm the token is a
PATIENT token before treating `sub` as a patient identity, and rejects tokens of the
wrong type.

Token validation follows a hybrid model. The API Gateway validates inbound external
requests and rejects unauthenticated traffic on protected paths before it reaches any
service. Individual services also validate tokens independently, enforcing a zero-trust
posture: internal traffic is verified per service, so a misconfigured or compromised
internal caller cannot bypass authorization by reaching a service directly. The two
layers are complementary; per-service validation is the authoritative security boundary,
and the gateway provides earlier, edge-level rejection.

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
* Minimal claims keep the token small and avoid leaking patient profile data to services
  that do not need it.

### Negative

* Per-service validation requires distributing the public key and configuring Spring
  Security in each validating service, adding setup overhead.
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