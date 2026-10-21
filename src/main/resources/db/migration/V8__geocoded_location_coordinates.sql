-- Preserve existing locations; coordinates are optional and written only after explicit geocoding selection.
ALTER TABLE locations ADD COLUMN IF NOT EXISTS latitude double precision;
ALTER TABLE locations ADD COLUMN IF NOT EXISTS longitude double precision;
-- No data backfill: an unverified location has NULL coordinates.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_locations_valid_geocoordinates'
      AND conrelid='locations'::regclass) THEN
    ALTER TABLE locations ADD CONSTRAINT ck_locations_valid_geocoordinates CHECK (
      (latitude IS NULL AND longitude IS NULL) OR
      (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    );
  END IF;
END $$;
