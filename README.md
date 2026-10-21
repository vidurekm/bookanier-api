# Bookanear – V6: onboarding, rezervace událostí a tmavý vzhled

Nové funkce a migrační instrukce jsou v kořenovém souboru `README_BOOKANEAR_V2.md` v kompletním ZIPu.

# Bookanear API – Java 21 / Spring Boot / PostgreSQL

A runnable **starter backend** for the agreed multi-tenant reservation model. This is not yet a complete production-grade booking platform.

## Run

Prerequisites: Java 21, Maven 3.9+, Docker.

```bash
docker compose up -d
mvn spring-boot:run
```

Flyway is currently disabled in `application.yml`. Apply only pending migrations to your existing database; do not rerun migrations already applied. Health: `GET http://localhost:8080/actuator/health`.

## REST API

| Method | URL | Purpose |
|---|---|---|
| GET | `/api/v1/organizations` | List organizations |
| POST | `/api/v1/organizations` | Create organization |
| GET/POST | `/api/v1/organizations/{org}/locations` | List/create locations |
| GET/POST | `/api/v1/organizations/{org}/services` | List/create services |
| GET | `/api/v1/organizations/{org}/members` | List members |
| GET/POST | `/api/v1/organizations/{org}/customers` | List/create customers |
| GET | `/api/v1/organizations/{org}/reservations?locationId=1` | List reservations |
| POST | `/api/v1/bookings` | Book one service with one worker |
| GET | `/api/v1/public/reservations/{token}` | Customer booking view |
| POST | `/api/v1/public/reservations/{token}/cancel` | Customer cancellation |
| POST | `/api/v1/public/reservations/{token}/reschedule` | Customer rescheduling |
| GET | `/actuator/health` | Health |

Example:

```bash
curl -X POST http://localhost:8080/api/v1/organizations \
  -H 'Content-Type: application/json' \
  -d '{"name":"Studio","slug":"studio"}'
```

After configuring location, service version, member, member-location, worker-service, location-service, weekly `open` and `working` schedule rules:

```bash
curl -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"organizationId":1,"locationId":1,"serviceId":1,"memberId":1,"startsAt":"2027-03-15T10:00:00+01:00","customerName":"Jan Novak","customerEmail":"jan@example.com"}'
```

The response includes a one-time secret `manageUrl`; handle it like a password. The GET endpoint shows basic booking metadata; POST endpoints modify it.

## Critical limitations before production

- **No authentication or authorization yet.** Administrative endpoints are intentionally unsecured scaffolding. Do not expose publicly. Add Spring Security with JWT/OIDC, role checks and organization isolation.
- **Catalog administration incomplete:** service versions, workers, assignments, opening hours, time off, prices, resources, payments and group sessions currently need separate admin implementation. The SQL schema supports them.
- Availability is checked **during booking**; a frontend-facing slot-search endpoint is not yet implemented.
- Booking supports one worker + one service only. Multi-service, group capacity and automatic resource allocation are not implemented.
- Overrides of type `available` are not processed. Night shifts are not supported by the starter's availability validator.
- Rescheduling keeps the existing snapshot and rejects a change that crosses to a different service version; a full re-pricing/consent workflow is needed.
- Payment processing, email/SMS delivery, temporary holds, refunds and waitlist orchestration are not implemented.
- No automated integration tests against PostgreSQL yet. Test migrations and transactional edge cases before production.
- The supplied v2 DDL has business-rule gaps; for example reservation resources are not automatically allocated, and the schema-level triggers do not fully validate organization availability.
- CORS, rate limiting, logging redaction, abuse prevention, secret management, idempotency keys, retention/GDPR controls and observability remain to be added.

## Project structure

- `BookingApplication.java`: entry point
- `CatalogController.java`: initial catalog and administration REST routes
- `BookingController.java`: booking/customer REST routes
- `BookingService.java`: transactional booking and token operations
- `Requests.java`: request DTOs
- `ErrorHandler.java`: error responses
- `V1__schema.sql`: existing database model, applied with Flyway

For real production use, the next milestone is to implement authentication and tenant authorization, complete the administration API and add integration tests with Testcontainers.


## Backoffice and Swagger UI (v0.2)

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Organization detail: `GET /api/v1/organizations/{org}`
- Service detail: `GET /api/v1/backoffice/organizations/{org}/services/{id}`
- Service edit/deactivate: `PUT/DELETE /api/v1/backoffice/organizations/{org}/services/{id}`
- Service price/duration history: `GET/POST /api/v1/backoffice/organizations/{org}/services/{id}/versions`
- Location detail/update: `GET/PUT /api/v1/backoffice/organizations/{org}/locations/{id}`
- Member detail/create/update: `GET/POST/PUT /api/v1/backoffice/organizations/{org}/members[/{id}]`
- Member services: `GET /members/{id}/services`, `PUT /members/{id}/services/{serviceId}` (under backoffice org prefix)
- Customer detail/update: `GET/PUT /api/v1/backoffice/organizations/{org}/customers/{id}`
- Reservation detail with items: `GET /api/v1/backoffice/organizations/{org}/reservations/{id}`
- Location schedule: `GET /api/v1/backoffice/organizations/{org}/locations/{id}/schedule`
- Add member schedule: `POST /api/v1/backoffice/organizations/{org}/schedule`
- Absences: `GET/POST /api/v1/backoffice/organizations/{org}/absences`

**SECURITY WARNING:** Backoffice endpoints currently have no authentication/authorization. Do not expose to the public internet until Spring Security and tenant-scoped authorization are implemented. Swagger is also open.

Service versions use non-overlapping PostgreSQL `daterange` periods; to change an existing open-ended version, close it first in a controlled migration/workflow before inserting the new version. Existing booking snapshots must not be rewritten.

## Organization registration (v3)

Flyway migration: `V2__organization_registration.sql` (apply **after** V1, do not edit V1).

- `POST /api/v1/registration` with `organizationName`, `slug`, `ownerName`, `email`, `password`.
- `POST /api/v1/registration/verify` with `{"token":"..."}`.
- Email URL: `${REGISTRATION_PUBLIC_URL}/verify-email?token=...`. The **frontend must implement this page**, extract the token and POST it to `/api/v1/registration/verify` after user confirmation. GET does not consume the token (avoids email-link-scanner activations).
- Token is 32 random bytes, URL-safe, SHA-256 hashed in DB, one-time, expires after 24h.
- Password stored as BCrypt hash. Duplicate email or slug yields HTTP 409 via existing handler.
- SMTP env: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `REGISTRATION_FROM_EMAIL`, `REGISTRATION_PUBLIC_URL`.
- For local SMTP testing use Mailpit or another development SMTP server on port 1025.
- Previous unauthenticated `POST /api/v1/organizations` was removed to prevent bypassing verification.

### Example

```bash
curl -X POST http://localhost:8080/api/v1/registration \
  -H 'Content-Type: application/json' \
  -d '{"organizationName":"Salon Praha","slug":"salon-praha","ownerName":"Jana Nova","email":"jana@example.com","password":"a-long-unique-password"}'
```

**Security limitation:** This starter still has no authentication or authorization. Do not expose backoffice routes publicly. Before production, add Spring Security, tenant-scoped authorization, email resend/rate limiting, and transactional email outbox (current SMTP send happens inside a DB transaction and cannot guarantee exactly-once delivery). Also consider supporting existing verified users creating additional organizations through an authenticated workflow.
