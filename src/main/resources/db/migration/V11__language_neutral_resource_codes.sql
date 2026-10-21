-- V11: all machine-readable resource identifiers are stable ASCII codes.
-- Existing IDs, bookings, service names and customer-provided labels are preserved.
-- This migration is transactional under Flyway/PostgreSQL.
ALTER TABLE resource_types ADD COLUMN code text;

-- Recognize legacy localized built-in types, preserve arbitrary tenant labels.
-- Duplicate recognizable names within one organization receive unique custom IDs.
WITH candidates AS (
  SELECT id, organization_id,
    CASE
      WHEN category = 'staff' OR lower(btrim(name)) IN ('pracovník','zaměstnanec','staff','staff member','pracownik','pracovník/člověk') THEN 'staff'
      WHEN lower(btrim(name)) IN ('stůl','stol','table','tisch','stolik','stół') THEN 'table'
      WHEN lower(btrim(name)) IN ('křeslo','kreslo','chair','fotel') THEN 'chair'
      WHEN lower(btrim(name)) IN ('vybavení','vybavenie','sprzęt','equipment') THEN 'equipment'
      WHEN lower(btrim(name)) IN ('nářadí','naradie','narzędzia','tools') THEN 'tools'
      ELSE 'custom_' || id::text
    END AS proposed_code
  FROM resource_types
), ranked AS (
  SELECT id, proposed_code,
    row_number() OVER (PARTITION BY organization_id, proposed_code ORDER BY id) AS seq
  FROM candidates
)
UPDATE resource_types rt
SET code = CASE WHEN ranked.seq=1 THEN ranked.proposed_code ELSE 'custom_' || rt.id::text END,
    category = CASE WHEN ranked.seq=1 AND ranked.proposed_code='staff' THEN 'staff' ELSE rt.category END
FROM ranked WHERE ranked.id=rt.id;

-- Keep old duplicated text fields consistent with the authoritative resource_type_id.
UPDATE resources r
SET resource_type = rt.code
FROM resource_types rt
WHERE r.resource_type_id = rt.id AND r.organization_id = rt.organization_id
  AND r.resource_type IS DISTINCT FROM rt.code;

-- Two-step rewrite avoids transient composite-PK conflicts (e.g. legacy "staff"
-- and "Pracovník" types in the same service that swap identifiers).
UPDATE service_resource_requirements
SET resource_type = '__bookanear_v11_' || resource_type_id::text
WHERE resource_type_id IS NOT NULL;
UPDATE service_resource_requirements req
SET resource_type = rt.code
FROM services s, resource_types rt
WHERE req.service_id=s.id AND req.resource_type_id=rt.id
  AND s.organization_id=rt.organization_id
  AND req.resource_type IS DISTINCT FROM rt.code;

-- Old text-only rows may still exist on installations upgraded from the original schema.
-- Fail explicitly instead of silently abandoning their references.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM resources WHERE resource_type_id IS NULL)
     OR EXISTS (SELECT 1 FROM service_resource_requirements WHERE resource_type_id IS NULL) THEN
    RAISE EXCEPTION 'V11: unresolved legacy resource_type_id. Repair legacy type associations before migration.';
  END IF;
END $$;

ALTER TABLE resource_types
  ALTER COLUMN code SET NOT NULL,
  ALTER COLUMN code SET DEFAULT ('custom_' || replace(gen_random_uuid()::text, '-', ''));
ALTER TABLE resource_types ADD CONSTRAINT resource_types_code_ascii
  CHECK (code ~ '^[a-z][a-z0-9_]*$');
CREATE UNIQUE INDEX resource_types_org_code_unique ON resource_types(organization_id, code);
-- Even direct SQL integrations must use stable, nonlocalized identifiers.
ALTER TABLE resources ADD CONSTRAINT resources_type_code_ascii
  CHECK (resource_type ~ '^[a-z][a-z0-9_]*$');
ALTER TABLE service_resource_requirements ADD CONSTRAINT service_requirements_type_code_ascii
  CHECK (resource_type ~ '^[a-z][a-z0-9_]*$');

-- Built-in templates are technical configuration; localized strings live in React.
UPDATE business_templates SET label = CASE template_key
    WHEN 'SALON' THEN 'Appointments'
    WHEN 'RESTAURANT' THEN 'Space reservations'
    WHEN 'RENTAL' THEN 'Equipment rentals'
    WHEN 'EVENTS' THEN 'Classes and events'
    WHEN 'GENERAL' THEN 'Other services'
    ELSE label END
WHERE template_key IN ('SALON','RESTAURANT','RENTAL','EVENTS','GENERAL');
UPDATE business_templates SET defaults = jsonb_set(defaults,'{resourceName}','"Chair"'::jsonb)
WHERE template_key='SALON' AND defaults->>'resourceName' IN ('Křeslo','Chair');
UPDATE business_templates SET defaults = defaults || '{"resourceName":"Table","serviceName":"Table reservation"}'::jsonb
WHERE template_key='RESTAURANT';
UPDATE business_templates SET defaults = defaults || '{"resourceName":"Equipment","serviceName":"Equipment rental"}'::jsonb
WHERE template_key='RENTAL';
