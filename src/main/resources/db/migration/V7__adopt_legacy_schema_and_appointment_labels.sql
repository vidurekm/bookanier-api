-- V7 is an idempotent compatibility step for databases migrated manually before Flyway.
-- Flyway migration V3 runs normally on a NEW database; existing pre-Flyway databases
-- are baselined at V6 and V7 repairs the missing V3 schema safely.
-- No table data is removed or truncated.
ALTER TABLE service_versions
    ADD COLUMN IF NOT EXISTS duration_mode text NOT NULL DEFAULT 'FIXED',
    ADD COLUMN IF NOT EXISTS max_duration_minutes integer;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'service_versions'::regclass AND conname = 'service_versions_duration_mode_check') THEN
        ALTER TABLE service_versions ADD CONSTRAINT service_versions_duration_mode_check CHECK (duration_mode IN ('FIXED','CUSTOM'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'service_versions'::regclass AND conname = 'service_versions_max_duration_minutes_check') THEN
        ALTER TABLE service_versions ADD CONSTRAINT service_versions_max_duration_minutes_check CHECK (max_duration_minutes IS NULL OR max_duration_minutes > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'service_versions'::regclass AND conname = 'service_versions_max_duration_valid') THEN
        ALTER TABLE service_versions ADD CONSTRAINT service_versions_max_duration_valid CHECK (max_duration_minutes IS NULL OR max_duration_minutes >= duration_minutes);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION validate_reservation_item() RETURNS trigger LANGUAGE plpgsql AS $$
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
 IF TG_OP='INSERT' AND (
   (v.duration_mode='FIXED' AND NEW.duration_minutes<>v.duration_minutes) OR
   (v.duration_mode='CUSTOM' AND (
      NEW.duration_minutes<v.duration_minutes OR
      (v.max_duration_minutes IS NOT NULL AND NEW.duration_minutes>v.max_duration_minutes))) OR
   NEW.price_amount<>effective_price OR NEW.currency<>effective_currency) THEN
  RAISE EXCEPTION 'Price/duration snapshot mismatch'; END IF;
 IF TG_OP='UPDATE' AND (NEW.price_amount IS DISTINCT FROM OLD.price_amount OR
   NEW.currency IS DISTINCT FROM OLD.currency OR
   NEW.duration_minutes IS DISTINCT FROM OLD.duration_minutes OR
   NEW.service_version_id IS DISTINCT FROM OLD.service_version_id) THEN
  RAISE EXCEPTION 'Snapshot immutable; reprice via explicit workflow'; END IF;
 RETURN NEW;
END $$;

-- Update only the old built-in label; respect tenant-independent custom changes.
UPDATE business_templates SET label='Služby s rezervací termínu'
WHERE template_key='SALON' AND label='Kadeřnictví a služby';
