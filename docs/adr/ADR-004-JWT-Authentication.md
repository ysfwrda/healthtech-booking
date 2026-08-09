# ADR-004: JWT Authentication

## Status

Accepted. This revision corrects the original, which described gateway edge
validation and universal role-claim enforcement that the code did not yet
implement, and which justified RS256 on the basis of a single issuer holding the
private key when in fact issuance is decentralized across services sharing one key
pair.

## Context

With Patient Service and Doctor Service introduced in Phase 2, the system needs to
authenticate users and authorize access to protected resources across services.
Three concerns must be addressed: where tokens are issued, where they are
validated, and what they carry.

Two signing algorithms were evaluated: HS256 (symmetric, shared secret) and RS256
(asymmetric, private and public key pair). HS256 requires every validating party
to hold the shared secret, so any service able to validate a token is also able to
forge one. RS256 separates signing from verification: holders of the private key
can issue tokens, and the public key can be distributed to any validator without
granting signing capability. RS256 is chosen so that validators, the gateway,
Appointment Service, and any future read-only service, can verify tokens without
being able to mint them.

A deliberate scope decision sits alongside the algorithm choice: issuance is
decentralized. Each domain service that authenticates its own user type issues
that type's tokens, and all issuers share one RS256 key pair. This is simpler than
standing up a dedicated issuer, at a cost recorded in the consequences. It has a
direct consequence for validation. Because the key pair is shared, a token's
signature proves authenticity but not which issuer minted it or which user type it
represents: any issuer's token verifies against the same public key. Token type
therefore cannot be inferred from the signature and must be carried explicitly in
the token and checked by every validator.

## Decision

Domain services issue RS256 tokens for their own user type. Patient Service issues
PATIENT tokens; Doctor Service issues DOCTOR tokens. All issuers share a single
RS256 key pair: each issuing service holds the private key for signing, and the
public key is distributed to every validating service.

Because the key pair is shared, token type is carried in a `role` claim and
enforced by every validator, not inferred from the signature. Each service that
authorizes an action reads `role`, rejects tokens of the wrong type, and only then
treats `sub` as an identity of that type. This enforcement is uniform: Patient
Service requires the PATIENT role on its protected endpoints, Doctor Service
requires DOCTOR, and Appointment Service requires PATIENT. In every service the
role is mapped from the claim to a Spring Security authority and asserted in the
authorization rules, so it is a security-layer rule rather than a per-endpoint
check that can be forgotten.

Validation follows a hybrid model. The API Gateway validates inbound external
requests, rejecting unauthenticated traffic on protected paths at the edge before
it reaches any service; its public-path allow-list is the union of the services'
public paths. Each service also validates independently as a resource server,
enforcing a zero-trust posture: internal traffic is verified per service, so a
caller reaching a service directly, bypassing the gateway, is still checked. The
two layers are complementary. Per-service validation is the authoritative security
boundary; the gateway provides earlier, edge-level rejection. The gateway checks
authenticity and expiry only. Role authorization stays in the services, because
which routes require which role is domain knowledge that does not belong in the
router.

Tokens carry three claims: `sub`, `role`, and `exp`.

* `sub` is the subject's identifier: the patient id in a PATIENT token, the doctor
  id in a DOCTOR token. Downstream services derive the acting user's identity from
  `sub` rather than from request bodies, so a caller cannot act on behalf of
  another user by supplying a different id in the payload.
* `role` identifies the user type (PATIENT or DOCTOR) and is the discriminator
  every validator enforces under the shared key.
* `exp` sets expiry. Access tokens are valid for one hour.

The claim set is intentionally minimal: services such as Appointment Service need
only the acting user's identity and role to authorize a request, not profile data.

Refresh tokens are not implemented in this phase and are recorded as future work.
A client-side inactivity timeout was specified for additional protection but is
not implemented in the frontend; it is recorded here as future work rather than
described as in place.

## Consequences

### Positive

* RS256 asymmetry means signing capability is never distributed to validators:
  services can verify tokens without being able to forge them. Only services
  holding the private key can issue tokens.
* Deriving identity from the validated `sub` claim, rather than from request
  bodies, closes a class of impersonation: a caller cannot act as another user by
  supplying that user's id in the payload.
* The hybrid model provides defense in depth: external traffic is filtered at the
  gateway, internal traffic is verified per service, and per-service validation
  remains authoritative even if a request bypasses the gateway.
* Role enforcement is uniform and lives in the security layer, so a wrong-type
  token is rejected consistently across services rather than depending on a check
  being remembered at each endpoint.
* Minimal claims keep the token small and avoid leaking patient profile data to
  services that do not need it.

### Negative

* Per-service validation requires distributing the public key and configuring
  each validator, including the gateway, which adds setup overhead.
* Without refresh tokens, a user whose session approaches the one-hour expiry must
  re-authenticate; this must be addressed before production use.
* The inactivity timeout is not implemented, so the shorter effective-session
  protection it was meant to provide is absent until it is built.
* Shared key with decentralized issuance is the least separated of the viable
  postures. More than one service holds the private key and can mint any token
  type, including another type's, so the guarantee of type separation rests
  entirely on the `role` claim being enforced, not on cryptography. A compromise of
  the private key on any issuing service forges tokens system-wide. This is an
  accepted tradeoff at the current scale, made explicit rather than implied.

### Evolution (known future work)

Two directions strengthen the trust model, in increasing order of separation.
Recorded so the tradeoff is explicit and the choice deliberate when scale or
requirements change.

* Per-issuer key pairs. Each issuer signs with its own key, and validators trust
  only the keys for the types they accept. Type separation becomes cryptographic
  rather than claim-based, and the failure mode becomes fail-safe: a
  misconfiguration rejects valid tokens rather than accepting invalid ones. A
  private-key compromise is contained to that issuer's type. The cost: validators
  that accept more than one type, such as the gateway, must hold multiple public
  keys and select by a `kid` header, and key delivery carries more than one private
  key. Role enforcement is still required to authorize within a type; per-issuer
  keys remove the claim's load for cross-type rejection, not role entirely.
* A dedicated Identity Service. A single issuer holds the private key exclusively
  and all domain services validate only. This restores a single issuer as the
  trust boundary and is the conventional enterprise pattern. The cost: a new
  service and the indirection of centralized issuance.

The appropriate step depends on the driver. Independent key rotation or
blast-radius containment points to per-issuer keys; a need to re-centralize the
trust boundary points to a dedicated issuer.
