# Specification: Doctor Service (Pass 1, Boilerplate Only)

## Context

This is the `doctor-service` module of the HealthTech Appointment Booking
Platform, a Spring Boot 3 / Java 21 microservice mono-repo. Doctor Service holds
doctor profile data that patients browse before booking. This is **pass 1**:
data layer only, no authentication, no slot/availability logic.

Follow the structure, naming, and conventions of the existing `patient-service`
module in this repository. Specifically:
- Constructor injection via Lombok `@RequiredArgsConstructor` with `private final`
  fields (never field injection).
- MapStruct mappers with `@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`.
- Package layout: `controller`, `service`, `repository`, `domain`, `dto`,
  `mapper`, `event`.
- Spring Boot 3.5.16, Java 21.

Match patient-service's style closely so the two services are consistent.

## Existing code (do NOT regenerate)

These already exist in `doctor-service/.../domain` and must be used as-is:
- `Doctor` entity (id UUID, firstName, lastName, email [unique], phoneNumber,
  `@Embedded Address`, `@ElementCollection Set<OpeningHours>` [LAZY],
  `@ManyToMany Set<Specialty>` [LAZY], registeredAt).
- `Specialty` entity (id UUID, name [unique]).
- `Address` `@Embeddable` (street, houseNumber, postalCode, city, state, country).
- `OpeningHours` `@Embeddable` (dayOfWeek, startTime, endTime).

Do not modify these entities. Do not add authentication fields to them.

## What to generate

### Repository
- `DoctorRepository extends JpaRepository<Doctor, UUID>`.
- Basic CRUD only. Do NOT add filtering or custom fetch queries: those are being
  implemented separately by hand. Leave the repository minimal.

### DTOs
- `DoctorResponse`: fields to expose are id, firstName, lastName, email,
  phoneNumber, address (as a nested response or flattened, match patient-service
  style), specialties (as a set of specialty names or a small specialty DTO),
  openingHours (as a set), registeredAt. Exclude nothing sensitive exists in
  pass 1, but never expose internal-only fields.
- `CreateDoctorRequest`: firstName, lastName, email, phoneNumber, address,
  specialty identifiers (how to reference specialties on create), openingHours.
  Add Jakarta validation constraints: `@NotBlank` on names/email/phone,
  `@Email` on email, `@NotNull` where appropriate. Mirror the validation style
  of patient-service's `RegisterRequest`.
- Small `AddressDto` and `OpeningHoursDto` (or `SpecialtyDto`) as needed for the
  request/response, following patient-service DTO conventions.

### Mapper
- MapStruct `DoctorMapper`: `Doctor -> DoctorResponse` and
  `CreateDoctorRequest -> Doctor`.
- IMPORTANT: `specialties` and `openingHours` are LAZY collections. The mapper
  only maps fields that are already loaded; it must NOT trigger lazy loading
  itself. Loading these collections (in-transaction or via fetch-join) is handled
  in the service/query layer separately. Write the mapper assuming the collections
  are already initialized when an entity is passed to it.

### Service
- `DoctorService` with: create a doctor (map request, save, return response),
  fetch a doctor by id (throw `DoctorNotFoundException` if absent, mapped later).
- Do NOT implement the filtered list method: that is being written separately.
- Keep the service transactional where collection access during mapping requires
  it, consistent with how patient-service handles its service methods.

### Controller
- `DoctorController`, base path `/api/doctors`.
- `POST /api/doctors` -> 201 Created, returns `DoctorResponse`. Open for now;
  add a `// TODO: doctor onboarding is open for portfolio scope; production
  requires admin + credential verification (LANR/eID), see README` comment.
  Use `@Valid` on the request body.
- `GET /api/doctors/{id}` -> 200, returns `DoctorResponse`; 404 if not found.
- Do NOT implement `GET /api/doctors` with filtering: that endpoint and its
  query are being written by hand separately.

### Exception
- `DoctorNotFoundException extends RuntimeException`, constructor taking the UUID,
  message like "Doctor not found: {id}". Mirror patient-service's
  `PatientNotFoundException`.

### Event
- `DoctorRegistered` event class in the `event` package, published to Kafka topic
  `doctor.registered` when a doctor is created. Mirror the structure of
  patient-service's `PatientRegistered` and appointment-service's
  `AppointmentBooked` (include an `eventId` UUID and a timestamp field for
  consistency).
- Fields: eventId, doctorId, firstName, lastName, registeredAt. Keep it lean:
  consumers (Appointment Service read-model for validation) primarily need the
  doctorId; name is included for any notification-side consumer.
- Wire a `KafkaTemplate<String, DoctorRegistered>` into `DoctorService` and
  publish after a successful save, mirroring how patient-service publishes
  `PatientRegistered` in `AuthService.register`.

## Conventions and constraints
- No em dashes anywhere in code comments or any generated docs. Use colons,
  semicolons, or separate sentences.
- Database-per-service (ADR-003): this service uses its own `doctor_db`. Do not
  reference other services' schemas.
- `phoneNumber` is NOT unique (clinic numbers may be shared); only `email` is
  unique.

## Tests
- Provide unit tests for `DoctorService` (create-doctor happy path including the
  `DoctorRegistered` publish, fetch-by-id found and not-found) following the
  Mockito patterns in patient-service's `AuthServiceTest` and `PatientServiceTest`
  (`@ExtendWith(MockitoExtension.class)`, AssertJ, strict stubbing). Mock the
  repository, mapper, and `KafkaTemplate`.

## Explicitly OUT OF SCOPE (do not generate)
- Any authentication: no login, no password handling, no JWT, no SecurityConfig,
  no security starters. Doctors are data only in pass 1.
- Slot availability computation, double-booking prevention, schedule logic.
- Doctor identity/credential verification.
- The `GET /api/doctors` filtered list endpoint, its repository query, and any
  custom fetch-join / Specification / EntityGraph queries: these are being
  implemented by hand separately. Do not generate them.
