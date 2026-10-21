-- Bookanear: configuration-driven booking modes and capacity-based group events.
-- Existing bookings and resource allocations are not modified.
CREATE TABLE business_templates (
  template_key text PRIMARY KEY,
  booking_model text NOT NULL CHECK (booking_model IN ('APPOINTMENT','RESOURCE','RENTAL','EVENT')),
  label text NOT NULL,
  steps jsonb NOT NULL DEFAULT '[]'::jsonb,
  defaults jsonb NOT NULL DEFAULT '{}'::jsonb,
  active boolean NOT NULL DEFAULT true
);
INSERT INTO business_templates(template_key,booking_model,label,steps,defaults) VALUES
('SALON','APPOINTMENT','Kadeřnictví a služby',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"services","when":"always"},{"id":"resources","when":"always"},{"id":"hours","when":"always"}]',
 '{"showStaffChoice":true,"trackResources":false,"resourceName":"Křeslo","resourceCount":1,"resourceCapacity":1}'),
('RESTAURANT','RESOURCE','Restaurace a kavárna',
 '[{"id":"locations","when":"always"},{"id":"resources","when":"always"},{"id":"services","when":"always"},{"id":"hours","when":"always"}]',
 '{"trackResources":true,"resourceName":"Stůl","resourceCount":8,"resourceCapacity":4,"showStaffChoice":false,"serviceName":"Rezervace stolu","serviceDuration":90}'),
('RENTAL','RENTAL','Půjčovna vybavení',
 '[{"id":"locations","when":"always"},{"id":"resources","when":"always"},{"id":"services","when":"always"},{"id":"hours","when":"always"}]',
 '{"trackResources":true,"resourceName":"Nářadí","resourceCount":3,"resourceCapacity":1,"showStaffChoice":false,"serviceName":"Pronájem nářadí","serviceDuration":60}'),
('EVENTS','EVENT','Skupinové lekce a události',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"events","when":"always"},{"id":"hours","when":"always"}]',
 '{"eventCapacity":12,"useWaitlist":false}'),
('GENERAL','APPOINTMENT','Jiné služby',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"services","when":"always"},{"id":"resources","when":"always"},{"id":"hours","when":"always"}]',
 '{}');

CREATE TABLE organization_booking_config (
 organization_id bigint PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
 template_key text REFERENCES business_templates(template_key),
 booking_model text CHECK (booking_model IN ('APPOINTMENT','RESOURCE','RENTAL','EVENT')),
 setup_status text NOT NULL DEFAULT 'draft' CHECK (setup_status IN ('draft','complete')),
 settings jsonb NOT NULL DEFAULT '{}'::jsonb,
 locked_at timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (setup_status <> 'complete' OR (template_key IS NOT NULL AND booking_model IS NOT NULL AND locked_at IS NOT NULL))
);
-- Existing organizations are asked to confirm their mode on next visit; no legacy rows are changed.
INSERT INTO organization_booking_config(organization_id) SELECT id FROM organizations;

CREATE TABLE event_series (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL REFERENCES organizations(id),
 location_id bigint NOT NULL,
 title text NOT NULL CHECK (length(trim(title)) > 0),
 description text,
 capacity integer NOT NULL CHECK (capacity > 0 AND capacity <= 10000),
 price numeric(12,2) NOT NULL DEFAULT 0 CHECK (price >= 0),
 currency char(3) NOT NULL DEFAULT 'CZK',
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,id),
 FOREIGN KEY (organization_id,location_id) REFERENCES locations(organization_id,id)
);
CREATE TABLE event_occurrences (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 series_id bigint NOT NULL,
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 capacity_override integer CHECK (capacity_override > 0 AND capacity_override <= 10000),
 status text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','cancelled')),
 CHECK (starts_at < ends_at),
 UNIQUE(organization_id,id),
 FOREIGN KEY (organization_id,series_id) REFERENCES event_series(organization_id,id)
);
CREATE INDEX event_occurrences_upcoming ON event_occurrences(organization_id,starts_at) WHERE status='scheduled';
CREATE TABLE event_registrations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 organization_id bigint NOT NULL,
 occurrence_id bigint NOT NULL,
 customer_name text NOT NULL CHECK (length(trim(customer_name)) > 0),
 customer_email text NOT NULL,
 customer_phone text,
 price_amount numeric(12,2) NOT NULL DEFAULT 0 CHECK (price_amount >= 0),
 currency char(3) NOT NULL DEFAULT 'CZK',
 seats integer NOT NULL CHECK (seats > 0 AND seats <= 100),
 status text NOT NULL DEFAULT 'confirmed' CHECK (status IN ('confirmed','cancelled')),
 token_hash char(64) NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(),
 cancelled_at timestamptz,
 FOREIGN KEY (organization_id,occurrence_id) REFERENCES event_occurrences(organization_id,id)
);
CREATE INDEX event_registrations_count ON event_registrations(occurrence_id) WHERE status='confirmed';
