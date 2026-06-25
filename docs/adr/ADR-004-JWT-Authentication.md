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

Domain services issue JWT tokens for their own user type, signed with RS256. Patient Service issues PATIENT tokens; Doctor Service will issue DOCTOR tokens when doctor authentication is introduced.
All issuers share a single RS256 key pair: the private key is held by each issuing service for signing, and the public key is distributed to all services for validation.

Token validation follows a hybrid model. The API Gateway validates all inbound
external requests and rejects unauthenticated traffic before it reaches any
service. Individual services also validate tokens independently, enforcing a
zero-trust posture for internal traffic and protecting against misconfigured or
buggy inter-service calls.

Tokens carry three claims: `sub` (patient ID), `role`, and `exp`. This is
intentionally minimal - downstream services such as Appointment Service need
only the patient identity and role to authorize a request, not profile data.
Access tokens are valid for one hour. A client-side inactivity timeout of 15
minutes is applied for additional security, covering scenarios such as account
theft or fraud where a shorter effective session window reduces exposure.

Refresh tokens are not implemented in this phase. They are documented here as a
known future improvement.

## Consequences

**Positive**
- Clear ownership of token issuance: Patient Service is the single source of
  truth for identity tokens, making the trust boundary explicit
- RS256 asymmetry means the signing capability is never distributed; services
  can validate without being able to forge tokens
- The hybrid validation model provides defense in depth: external traffic is
  filtered at the gateway, internal traffic is independently verified per service
- Minimal claims keep the token small and avoid leaking patient profile data
  to services that do not need it

**Negative**
- Per-service validation requires distributing the public key and configuring
  Spring Security in each service, adding setup overhead
- Without refresh tokens, a user whose session approaches the one-hour expiry
  must re-authenticate; this will need to be addressed before any production use
- The 15-minute inactivity timeout is client-enforced and therefore not a
  server-side security guarantee; a determined client could ignore it
- Sharing a private key across multiple issuing services (Patient Service, Doctor Service) is a pragmatic tradeoff at this scale.
With more than two issuing services or a need for independent key rotation per service, a dedicated Identity Service becomes the appropriate evolution. This is documented as a known future improvement.