# Bookanear – V6: onboarding, rezervace událostí a tmavý vzhled

Nové funkce a migrační instrukce jsou v kořenovém souboru `README_BOOKANEAR_V2.md` v kompletním ZIPu.

# Bookanear API – Java 21 / Spring Boot / PostgreSQL

A runnable **starter backend** for the agreed multi-tenant reservation model. This is not yet a complete production-grade booking platform.

## Run

Location archiving and deactivation are blocked while pending/confirmed bookings
or confirmed event registrations have not ended. Opening-hour and date-override
changes are transactional: conflicting pending/confirmed bookings, including
ongoing bookings, cause HTTP 409 and roll back the schedule change.
`dryRun=true` previews conflicts without saving. The legacy
`cancelConflictingReservations` parameter does not bypass this protection;
affected bookings must be moved or cancelled manually first.

Prerequisites: Java 21, Maven 3.9+, Docker.

```bash
cd bookanear-api
mvn spring-boot:run
```

When running locally, Spring loads the ignored `bookanear-api/.env.local` file. It contains the local configuration defaults and must be updated with valid database and SMTP settings. Docker Compose uses `.env` instead. Flyway applies pending migrations at startup. Health: `GET http://localhost:8080/actuator/health`.

The optional in-app help assistant uses any OpenAI-compatible chat API (`AI_HELP_BASE_URL`, default OpenAI; free options: Google Gemini `https://generativelanguage.googleapis.com/v1beta/openai` with `gemini-2.0-flash`, or Groq `https://api.groq.com/openai/v1` with `llama-3.3-70b-versatile`). Set `AI_HELP_API_KEY` in `.env.local` for local runs or `.env` for Docker Compose; `AI_HELP_MODEL` defaults to `gpt-4o-mini` (the older `OPENAI_API_KEY` / `OPENAI_MODEL` names still work as fallbacks). Keep the key server-side and out of source control. The assistant sends chat messages to the configured provider, does not receive organization data, and is restricted by its instructions to Bookanear usage help. Its verified product guide is `src/main/resources/ai-help/product-guide.md`; Maven packages the current guide into every API build. Update this guide when product behavior changes, then rebuild and redeploy the API. A build packages the guide but does not infer new behavior from source code automatically. The assistant stores up to the latest 200 chat messages per authenticated user in PostgreSQL. Users can restore this history when reopening the assistant or signing in again, and delete it through the assistant's trash button. Migration `V22__ai_help_conversation_history.sql` creates the history table; messages are deleted automatically when the account is deleted.

To empty all application tables without removing the schema or Flyway migration history, open `scripts/clean-database.sql` in DBeaver, replace `REPLACE_WITH_DATABASE_NAME` with the exact target database name shown in DBeaver, then execute the script. It refuses to run if the placeholder was not changed or the active connection is to a different database:

This permanently deletes application data and resets table identity sequences. `CASCADE` also clears tables connected by foreign keys. It does not run migrations or remove `flyway_schema_history`.

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
- Member detail/update: `GET/PUT /api/v1/backoffice/organizations/{org}/members[/{id}]`
- Invite a member: owner-only `POST /api/v1/backoffice/organizations/{org}/invitations` with `{email,displayName,role}`; list pending invitations with `GET .../{org}/invitations`.
- Accept an invitation: `GET /api/v1/invitations?token=...` returns invitation details; `POST /api/v1/invitations/accept` accepts the invite. New invitees provide a password; existing users must authenticate with the same email address.
- Initial setup collects each team member's email. Completing setup creates the member, preserves its roles, permissions, service/location/schedule assignments, then sends the same invitation linked to that member record. Draft saves may omit the email; completion requires a valid, unique address.
- Invitation tokens are 32 random bytes, stored only as SHA-256 hashes, single-use, and expire after 24 hours. Invitation email uses the existing SMTP sender and `${REGISTRATION_HOME_URL}` frontend base URL.
- Member services: `GET /members/{id}/services`, `PUT /members/{id}/services/{serviceId}` (under backoffice org prefix)
- Customer detail/update: `GET/PUT /api/v1/backoffice/organizations/{org}/customers/{id}`
- Reservation detail with items: `GET /api/v1/backoffice/organizations/{org}/reservations/{id}`
- Location schedule: `GET /api/v1/backoffice/organizations/{org}/locations/{id}/schedule`
- Add member schedule: `POST /api/v1/backoffice/organizations/{org}/schedule`
- Absences: `GET/POST /api/v1/backoffice/organizations/{org}/absences`

Backoffice endpoints use bearer authentication and tenant-scoped role authorization. Swagger remains publicly accessible.

Service versions use non-overlapping PostgreSQL `daterange` periods; to change an existing open-ended version, close it first in a controlled migration/workflow before inserting the new version. Existing booking snapshots must not be rewritten.

## Organization registration (v3)

Flyway migration: `V2__organization_registration.sql` (apply **after** V1, do not edit V1).

- `POST /api/v1/registration` with `organizationName`, `ownerName`, `email`, `password` and optional `language` (`cs`, `sk`, `pl` or `en`).
- `POST /api/v1/registration/verify` with `{"token":"..."}`.
- The verification email is localized to the language selected on the registration form. Its one-time link opens the matching localized frontend page.
- Token is 32 random bytes, URL-safe, SHA-256 hashed in DB, one-time, expires after 24h.
- Password stored as BCrypt hash. Duplicate email or slug yields HTTP 409 via existing handler.
- Local configuration, including SMTP defaults, is in the ignored `.env.local` file. Do not commit real credentials.
- For local SMTP testing, Mailpit's web interface is available at `http://127.0.0.1:18082` and its host SMTP port is `11025` (the container SMTP port remains `1025`).
- Previous unauthenticated `POST /api/v1/organizations` was removed to prevent bypassing verification.

### Example

```bash
curl -X POST http://localhost:8080/api/v1/registration \
  -H 'Content-Type: application/json' \
  -d '{"organizationName":"Salon Praha","slug":"salon-praha","ownerName":"Jana Nova","email":"jana@example.com","password":"a-long-unique-password"}'
```

**Security limitation:** This starter still has no authentication or authorization. Do not expose backoffice routes publicly. Before production, add Spring Security, tenant-scoped authorization, email resend/rate limiting, and transactional email outbox (current SMTP send happens inside a DB transaction and cannot guarantee exactly-once delivery). Also consider supporting existing verified users creating additional organizations through an authenticated workflow.
