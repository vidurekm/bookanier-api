# Bookanear API V6

Nová Flyway migrace `V6__booking_models_onboarding_events.sql`, konfigurační a event REST API, změna balíčku na `online.bookanear.api`.

Před migrací proveďte zálohu databáze. Registrace nových organizací vytvoří záznam `organization_booking_config` ve stavu `draft`.

Spusťte Maven build na Java 21 a integrační testy s PostgreSQL 16 / Docker Testcontainers. Bez běžící databáze nelze ověřit chování Flyway ani souběžné rezervace.

`REGISTRATION_PUBLIC_URL` nastavte na URL frontendu, např. `https://bookanear.online`.
