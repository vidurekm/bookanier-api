-- Univerzalni multi-tenant rezervacni system / PostgreSQL 15+ / verze 2
-- Casove intervaly [start,end), UTC TIMESTAMPTZ; lokalni datum podle timezone provozovny.
-- Jde o produkcne orientovane DDL jadro; workflow dostupnosti, plateb a notifikaci resi aplikace.
BEGIN;
-- Baseline for fresh PostgreSQL 16 installations. All required schema objects are created here.

CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name text NOT NULL,
 email text, phone text, default_timezone text NOT NULL DEFAULT 'Europe/Prague',
 booking_enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE users (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 email text NOT NULL, name text NOT NULL, password_hash text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_ci ON users (lower(email));
CREATE TABLE organization_members (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 user_id bigint REFERENCES users(id), display_name text NOT NULL,
 role text NOT NULL DEFAULT 'worker' CHECK (role IN ('owner','admin','worker')),
 active boolean NOT NULL DEFAULT true,
 UNIQUE (organization_id,id), UNIQUE (organization_id,user_id)
);
CREATE TABLE locations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 name text NOT NULL, address text, timezone text NOT NULL DEFAULT 'Europe/Prague',
 active boolean NOT NULL DEFAULT true,
 UNIQUE (organization_id,id)
);
CREATE TABLE member_locations (
 organization_id bigint NOT NULL, member_id bigint NOT NULL, location_id bigint NOT NULL,
 PRIMARY KEY (member_id,location_id),
 FOREIGN KEY (organization_id,member_id) REFERENCES organization_members(organization_id,id),
 FOREIGN KEY (organization_id,location_id) REFERENCES locations(organization_id,id)
);
CREATE TABLE customers (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 user_id bigint REFERENCES users(id), name text NOT NULL, email text, phone text,
 notes text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (organization_id,id)
);
CREATE INDEX customers_email_idx ON customers(organization_id,lower(email));

CREATE TABLE services (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 name text NOT NULL, description text, active boolean NOT NULL DEFAULT true,
 UNIQUE (organization_id,id)
);
-- Parametry sluzby podle lokalniho dne provedeni. Intervaly se nesmi prekryvat.
CREATE TABLE service_versions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 service_id bigint NOT NULL REFERENCES services(id),
 valid_during daterange NOT NULL,
 duration_minutes integer NOT NULL CHECK (duration_minutes > 0),
 slot_interval_minutes integer NOT NULL CHECK (slot_interval_minutes > 0),
 buffer_before_minutes integer NOT NULL DEFAULT 0 CHECK (buffer_before_minutes >= 0),
 buffer_after_minutes integer NOT NULL DEFAULT 0 CHECK (buffer_after_minutes >= 0),
 price numeric(12,2) NOT NULL CHECK (price >= 0), currency char(3) NOT NULL DEFAULT 'CZK',
 CHECK (NOT isempty(valid_during) AND lower_inc(valid_during) AND NOT upper_inc(valid_during)),
 UNIQUE (service_id,id),
 EXCLUDE USING gist (service_id WITH =,valid_during WITH &&)
);
CREATE TABLE worker_services (
 organization_id bigint NOT NULL,
 member_id bigint NOT NULL, service_id bigint NOT NULL,
 active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(member_id,service_id),
 FOREIGN KEY(organization_id,member_id) REFERENCES organization_members(organization_id,id),
 FOREIGN KEY(organization_id,service_id) REFERENCES services(organization_id,id)
);
CREATE TABLE location_services (
 organization_id bigint NOT NULL, location_id bigint NOT NULL, service_id bigint NOT NULL,
 active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(location_id,service_id),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY(organization_id,service_id) REFERENCES services(organization_id,id)
);

-- Opakovane rozvrhy: pro nocni smeny pouzijte dva intervaly na ruznych dnech.
-- effective_dates podporuje budoucni zmeny rozvrhu.
CREATE TABLE schedule_rules (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 location_id bigint, member_id bigint,
 kind text NOT NULL CHECK (kind IN ('open','working')),
 weekday smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
 start_time time NOT NULL, end_time time NOT NULL,
 effective_dates daterange NOT NULL DEFAULT daterange(current_date,NULL,'[)'),
 CHECK (start_time < end_time),
 CHECK (NOT isempty(effective_dates)),
 CHECK ((kind='open' AND location_id IS NOT NULL AND member_id IS NULL)
     OR (kind='working' AND member_id IS NOT NULL AND location_id IS NOT NULL)),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY(organization_id,member_id) REFERENCES organization_members(organization_id,id)
);
-- Datumove vyjimky: pridana smena, uzavreni, dovolená, prestavka.
CREATE TABLE availability_overrides (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 location_id bigint, member_id bigint,
 override_type text NOT NULL CHECK (override_type IN ('available','unavailable')),
 period tstzrange NOT NULL, reason text,
 CHECK (location_id IS NOT NULL OR member_id IS NOT NULL),
 CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period)),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY(organization_id,member_id) REFERENCES organization_members(organization_id,id)
);
CREATE INDEX availability_overrides_period_idx ON availability_overrides USING gist(period);

CREATE TABLE resources (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL, location_id bigint NOT NULL,
 name text NOT NULL, resource_type text NOT NULL DEFAULT 'equipment',
 active boolean NOT NULL DEFAULT true,
 UNIQUE(organization_id,id), UNIQUE(organization_id,location_id,id),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id)
);
-- V teto verzi jsou pozadavky na zdroje explicitne prirazovany v rezervaci.
-- Pro automaticky vyber alternativnich zdroju lze pridat skupiny/typy zdroju.
CREATE TABLE service_resource_requirements (
 service_id bigint NOT NULL REFERENCES services(id),
 resource_type text NOT NULL, quantity integer NOT NULL DEFAULT 1 CHECK(quantity > 0),
 PRIMARY KEY(service_id,resource_type)
);

CREATE TABLE booking_policies (
 organization_id bigint NOT NULL, location_id bigint NOT NULL,
 min_notice_minutes integer NOT NULL DEFAULT 0 CHECK(min_notice_minutes >= 0),
 max_advance_days integer NOT NULL DEFAULT 180 CHECK(max_advance_days > 0),
 cancellation_notice_minutes integer NOT NULL DEFAULT 1440 CHECK(cancellation_notice_minutes >= 0),
 reschedule_notice_minutes integer NOT NULL DEFAULT 1440 CHECK(reschedule_notice_minutes >= 0),
 require_payment boolean NOT NULL DEFAULT false,
 PRIMARY KEY(location_id),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id)
);

-- Jedna navsteva muze obsahovat vice sluzeb; castky jsou snapshoty.
CREATE TABLE reservations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 location_id bigint NOT NULL,
 customer_id bigint,
 customer_name text NOT NULL, customer_email text, customer_phone text,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN
  ('pending','confirmed','completed','cancelled','no_show')),
 hold_expires_at timestamptz,
 currency char(3) NOT NULL DEFAULT 'CZK',
 total_amount numeric(12,2) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,location_id,id),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id),
 CHECK(status <> 'pending' OR hold_expires_at IS NOT NULL)
);
CREATE TABLE reservation_items (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL, location_id bigint NOT NULL,
 reservation_id bigint NOT NULL,
 service_id bigint NOT NULL, service_version_id bigint NOT NULL,
 member_id bigint NOT NULL,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 occupied_period tstzrange NOT NULL,
 duration_minutes integer NOT NULL CHECK(duration_minutes > 0),
 price_amount numeric(12,2) NOT NULL CHECK(price_amount >= 0),
 currency char(3) NOT NULL,
 item_status text NOT NULL DEFAULT 'active' CHECK(item_status IN ('active','cancelled')),
 UNIQUE(organization_id,id), UNIQUE(organization_id,location_id,id),
 FOREIGN KEY(organization_id,location_id,reservation_id)
   REFERENCES reservations(organization_id,location_id,id),
 FOREIGN KEY(organization_id,service_id) REFERENCES services(organization_id,id),
 FOREIGN KEY(service_id,service_version_id) REFERENCES service_versions(service_id,id),
 FOREIGN KEY(organization_id,member_id) REFERENCES organization_members(organization_id,id),
 FOREIGN KEY(member_id,service_id) REFERENCES worker_services(member_id,service_id),
 FOREIGN KEY(member_id,location_id) REFERENCES member_locations(member_id,location_id),
 FOREIGN KEY(location_id,service_id) REFERENCES location_services(location_id,service_id),
 CHECK(starts_at < ends_at),
 CHECK(NOT isempty(occupied_period) AND occupied_period = tstzrange(lower(occupied_period),upper(occupied_period),'[)')),
 CHECK(lower(occupied_period) <= starts_at AND upper(occupied_period) >= ends_at)
);
-- Exclusion pro aktivni polozky; storno cele rezervace musi take deaktivovat polozky.
ALTER TABLE reservation_items ADD CONSTRAINT no_worker_overlap
 EXCLUDE USING gist(member_id WITH =,occupied_period WITH &&)
 WHERE(item_status='active');
CREATE INDEX reservation_items_starts_idx ON reservation_items(organization_id,starts_at);

CREATE TABLE reservation_resources (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL, location_id bigint NOT NULL,
 reservation_item_id bigint NOT NULL, resource_id bigint NOT NULL,
 occupied_period tstzrange NOT NULL,
 active boolean NOT NULL DEFAULT true,
 FOREIGN KEY(organization_id,location_id,reservation_item_id)
  REFERENCES reservation_items(organization_id,location_id,id),
 FOREIGN KEY(organization_id,location_id,resource_id)
  REFERENCES resources(organization_id,location_id,id),
 CHECK(NOT isempty(occupied_period) AND lower_inc(occupied_period) AND NOT upper_inc(occupied_period)),
 EXCLUDE USING gist(resource_id WITH =,occupied_period WITH &&) WHERE(active)
);

-- Verejne URL obsahuji nahodny token, DB uklada pouze SHA-256 hash.
CREATE TABLE reservation_tokens (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
 token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32),
 scope text NOT NULL DEFAULT 'manage' CHECK(scope IN ('view','manage')),
 expires_at timestamptz NOT NULL, revoked_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reservation_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id),
 actor_user_id bigint REFERENCES users(id),
 event_type text NOT NULL,
 details jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification_outbox (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 reservation_id bigint REFERENCES reservations(id),
 channel text NOT NULL CHECK(channel IN ('email','sms','push')),
 recipient text NOT NULL, template_key text NOT NULL,
 payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 status text NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','sending','sent','failed')),
 scheduled_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts >= 0)
);
CREATE INDEX notification_outbox_due_idx ON notification_outbox(status,scheduled_at);
CREATE TABLE payments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id),
 provider text NOT NULL, provider_payment_id text,
 amount numeric(12,2) NOT NULL CHECK(amount >= 0), currency char(3) NOT NULL,
 status text NOT NULL CHECK(status IN ('pending','authorized','paid','failed','refunded','partially_refunded')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(provider,provider_payment_id)
);
CREATE TABLE payment_refunds (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id bigint NOT NULL REFERENCES payments(id),
 amount numeric(12,2) NOT NULL CHECK(amount > 0),
 status text NOT NULL CHECK(status IN ('pending','succeeded','failed')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE waitlist_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL, location_id bigint NOT NULL,
 customer_id bigint, service_id bigint NOT NULL,
 preferred_from timestamptz NOT NULL, preferred_to timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'waiting' CHECK(status IN ('waiting','offered','fulfilled','cancelled','expired')),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY(organization_id,customer_id) REFERENCES customers(organization_id,id),
 FOREIGN KEY(organization_id,service_id) REFERENCES services(organization_id,id),
 CHECK(preferred_from < preferred_to)
);

-- Zabrani pozdejsi uprave cenoveho snapshotu verze pouzite v rezervaci.
CREATE FUNCTION protect_used_service_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM reservation_items WHERE service_version_id=OLD.id) THEN
  RAISE EXCEPTION 'Service version already used in reservations; create a new version';
 END IF;
 RETURN COALESCE(NEW,OLD);
END $$;
CREATE TRIGGER protect_service_version BEFORE UPDATE OR DELETE ON service_versions
 FOR EACH ROW EXECUTE FUNCTION protect_used_service_version();

-- Snapshot pri vytvoreni: delka, cena, mena, obsazeny interval a platnost verze.
CREATE FUNCTION validate_reservation_item() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v record; v_date date; effective_price numeric(12,2); effective_currency char(3);
BEGIN
 SELECT sv.*,l.timezone INTO v FROM service_versions sv
 JOIN services s ON s.id=sv.service_id
 JOIN locations l ON l.organization_id=s.organization_id
 WHERE sv.id=NEW.service_version_id AND sv.service_id=NEW.service_id
   AND l.id=NEW.location_id AND s.organization_id=NEW.organization_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Invalid service version or tenant'; END IF;
 v_date := (NEW.starts_at AT TIME ZONE v.timezone)::date;
 IF NOT(v.valid_during @> v_date) THEN RAISE EXCEPTION 'Service version not valid for visit date'; END IF;
 IF NEW.ends_at <> NEW.starts_at + make_interval(mins=>NEW.duration_minutes) THEN
  RAISE EXCEPTION 'Duration mismatch'; END IF;
 IF NEW.occupied_period <> tstzrange(
  NEW.starts_at-make_interval(mins=>v.buffer_before_minutes),
  NEW.ends_at+make_interval(mins=>v.buffer_after_minutes),'[)') THEN
  RAISE EXCEPTION 'Occupied interval mismatch'; END IF;
 effective_price := v.price;
 effective_currency := v.currency;
 SELECT spo.price, spo.currency INTO effective_price, effective_currency
 FROM service_price_overrides spo
 WHERE spo.organization_id=NEW.organization_id AND spo.service_id=NEW.service_id
   AND (spo.member_id IS NULL OR spo.member_id=NEW.member_id)
   AND (spo.location_id IS NULL OR spo.location_id=NEW.location_id)
   AND spo.valid_during @> v_date
 ORDER BY (CASE WHEN spo.member_id IS NOT NULL THEN 2 ELSE 0 END +
           CASE WHEN spo.location_id IS NOT NULL THEN 1 ELSE 0 END) DESC
 LIMIT 1;
 IF NOT FOUND THEN
   effective_price := v.price;
   effective_currency := v.currency;
 END IF;
 IF TG_OP='INSERT' AND (NEW.duration_minutes<>v.duration_minutes OR
   NEW.price_amount<>effective_price OR NEW.currency<>effective_currency) THEN
  RAISE EXCEPTION 'Price/duration snapshot mismatch'; END IF;
 IF TG_OP='UPDATE' AND (NEW.price_amount IS DISTINCT FROM OLD.price_amount OR
   NEW.currency IS DISTINCT FROM OLD.currency OR
   NEW.duration_minutes IS DISTINCT FROM OLD.duration_minutes OR
   NEW.service_version_id IS DISTINCT FROM OLD.service_version_id) THEN
  RAISE EXCEPTION 'Snapshot immutable; reprice via explicit workflow'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reservation_item_validate BEFORE INSERT OR UPDATE ON reservation_items
 FOR EACH ROW EXECUTE FUNCTION validate_reservation_item();

-- Sjednoceni stavu: zruseni rezervace uvolni blokace polozek a zdroju.
CREATE FUNCTION release_cancelled_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='cancelled' AND OLD.status<>'cancelled' THEN
  UPDATE reservation_items SET item_status='cancelled' WHERE reservation_id=NEW.id;
  UPDATE reservation_resources SET active=false
   WHERE reservation_item_id IN (SELECT id FROM reservation_items WHERE reservation_id=NEW.id);
 END IF;
 NEW.updated_at := now();
 RETURN NEW;
END $$;
-- BEFORE trigger: aktualizace podrizenych radku je v ramci stejne transakce.
CREATE TRIGGER reservation_cancel_release BEFORE UPDATE OF status ON reservations
 FOR EACH ROW EXECUTE FUNCTION release_cancelled_reservation();


-- Skupinove udalosti / kurzy: kapacita se kontroluje v transakci se zamcenim session radku.
CREATE TABLE group_sessions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 location_id bigint NOT NULL,
 service_id bigint NOT NULL,
 host_member_id bigint,
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 capacity integer NOT NULL CHECK (capacity > 0),
 status text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','cancelled','completed')),
 UNIQUE (organization_id,location_id,id),
 FOREIGN KEY (organization_id,location_id) REFERENCES locations(organization_id,id),
 FOREIGN KEY (organization_id,service_id) REFERENCES services(organization_id,id),
 FOREIGN KEY (organization_id,host_member_id) REFERENCES organization_members(organization_id,id),
 CHECK (starts_at < ends_at)
);
CREATE TABLE group_session_attendees (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 location_id bigint NOT NULL,
 session_id bigint NOT NULL,
 reservation_id bigint NOT NULL,
 seats integer NOT NULL DEFAULT 1 CHECK (seats > 0),
 status text NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved','cancelled','attended','no_show')),
 UNIQUE (session_id,reservation_id),
 FOREIGN KEY (organization_id,location_id,session_id) REFERENCES group_sessions(organization_id,location_id,id),
 FOREIGN KEY (organization_id,location_id,reservation_id) REFERENCES reservations(organization_id,location_id,id)
);
CREATE INDEX group_session_attendees_active_idx ON group_session_attendees(session_id) WHERE status='reserved';

-- Individuální cenove odchylky podle pracovnika a pobocky (optional).
CREATE TABLE service_price_overrides (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 service_id bigint NOT NULL,
 member_id bigint,
 location_id bigint,
 valid_during daterange NOT NULL,
 price numeric(12,2) NOT NULL CHECK(price >= 0),
 currency char(3) NOT NULL DEFAULT 'CZK',
 CHECK(member_id IS NOT NULL OR location_id IS NOT NULL),
 CHECK(NOT isempty(valid_during) AND lower_inc(valid_during) AND NOT upper_inc(valid_during)),
 FOREIGN KEY (organization_id,service_id) REFERENCES services(organization_id,id),
 FOREIGN KEY (organization_id,member_id) REFERENCES organization_members(organization_id,id),
 FOREIGN KEY (organization_id,location_id) REFERENCES locations(organization_id,id)
);
-- Prekryv pro stejny scope vcetne NULL hodnot (NULLS NOT DISTINCT nelze pouzit v EXCLUDE).
ALTER TABLE service_price_overrides ADD CONSTRAINT no_overlapping_price_overrides
 EXCLUDE USING gist (service_id WITH =,
   (coalesce(member_id,0::bigint)) WITH =,
   (coalesce(location_id,0::bigint)) WITH =,
   valid_during WITH &&);

-- Skutecna historie zmen stavu rezervace (trigger zapise minimalni audit automaticky).
CREATE FUNCTION audit_reservation_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status THEN
  INSERT INTO reservation_events(reservation_id,event_type,details)
  VALUES (NEW.id,'status_changed',jsonb_build_object('from',OLD.status,'to',NEW.status));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reservation_status_audit AFTER UPDATE OF status ON reservations
 FOR EACH ROW EXECUTE FUNCTION audit_reservation_status();

-- Sjednoceni zruseni jednotlive polozky a prirazeneho zdroje.
CREATE FUNCTION release_cancelled_item_resources() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.item_status='cancelled' AND OLD.item_status<>'cancelled' THEN
  UPDATE reservation_resources SET active=false WHERE reservation_item_id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reservation_item_release AFTER UPDATE OF item_status ON reservation_items
 FOR EACH ROW EXECUTE FUNCTION release_cancelled_item_resources();


-- Organization registration and email verification (formerly V2).
ALTER TABLE organizations ADD COLUMN registration_status text NOT NULL DEFAULT 'active'
  CHECK (registration_status IN ('pending_email_verification','active','suspended'));
ALTER TABLE organizations ADD COLUMN verified_at timestamptz;
ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE organization_registration_tokens (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
 user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 token_hash char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX organization_registration_one_open_token
 ON organization_registration_tokens (organization_id) WHERE consumed_at IS NULL;
CREATE INDEX organization_registration_tokens_expiry_idx ON organization_registration_tokens(expires_at)
 WHERE consumed_at IS NULL;

-- Fields already present in the reference PostgreSQL database.
ALTER TABLE organizations ADD COLUMN public_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE organizations ADD CONSTRAINT uq_organizations_public_id UNIQUE (public_id);
ALTER TABLE users ADD COLUMN is_superadmin boolean NOT NULL DEFAULT false;
CREATE INDEX users_superadmin_idx ON users (id) WHERE is_superadmin = true;

COMMIT;

-- INTEGRACNI PRAVIDLA (implementovat v jedne transakci v backendu):
-- 1. Pred vytvorenim/zmenou rezervace overit organizaci, pobocku, pracovnika, sluzbu,
--    opakovane rozvrhy, datumove vyjimky, booking_policies, zdroje a pripadne platbu.
-- 2. Pro konzistentni kontrolu absenci a uzavreni serializovat zapisy na uzamcenych
--    organization + organization_members + resources radcich (vzdy ve stejnem poradi).
-- 3. Pri rezervaci nejprve zamknout organizaci/pracovnika/zdroje, potom overit
--    dostupnost a vlozit polozky. EXCLUDE omezeni je posledni ochrana pred race condition.
-- 4. Stav pending blokuje kapacitu, proto expirovane holdy MUSI pravidelne
--    prejit do cancelled vcetne uvolneni polozek/zdroju.
-- 5. Total_amount v reservations je snapshot souctu polozek (plus pripadne slevy/dane).
--    Konzistenci souctu a plateb musi zajistit transakcni aplikační workflow.
-- 6. Token generovat CSPRNG (alespon 32 bajtu); hash SHA-256; pri pouziti kontrolovat
--    platnost, scope a revokaci. Zmenu/zruseni potvrdit POST, nikdy pouhym GET.
-- 7. Pri zruseni jednotlive polozky uvolnit i jeji reservation_resources.
-- 8. Pro skupinove lekce, balicky, vouchery, varianty cen podle pracovnika a
--    zdroje s kapacitou >1 jsou potrebna dalsi rozsireni.
-- 9. Bezpecnost: tenant-scoped authorization na kazdem endpointu, pripadne RLS;
--    neni zahrnuto automaticky, protoze zalezi na zpusobu DB autentizace.

-- DULEZITE PRO PRODUKCI:
-- - Zapis rezervaci provadet pres jediny servisni workflow s transakci a konzistentnim lock order.
-- - Cena na nove polozce se overuje proti nejkonkretnejsi platne cenove vyjimce.
-- - Group session attendees: kontrolovat SUM(seats) <= capacity v transakci
--   se SELECT ... FOR UPDATE nad group_sessions; pouhy CHECK nestaci.
-- - Storna/expirace holdů a session attendees synchronizovat v aplikaci.
-- - Vypocet dostupnosti zohlednuje casove zony, DST, rozvrhy, overrides a kapacity.
-- - Nastavit role/GRANT a tenant authorization; DDL samo neni kompletni RLS.
