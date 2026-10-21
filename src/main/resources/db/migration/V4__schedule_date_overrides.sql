-- The provided V1 baseline does not contain the date-specific schedule tables,
-- although BackofficeService and BookingService already use them. Install only
-- if an equivalent migration has not already been recorded by Flyway.
CREATE TABLE IF NOT EXISTS schedule_date_overrides (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    location_id BIGINT NOT NULL REFERENCES locations(id),
    member_id BIGINT REFERENCES organization_members(id),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('open','working')),
    work_date DATE NOT NULL,
    CHECK ((kind='open' AND member_id IS NULL) OR (kind='working' AND member_id IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_schedule_date_open
    ON schedule_date_overrides(location_id,work_date) WHERE kind='open';
CREATE UNIQUE INDEX IF NOT EXISTS uq_schedule_date_working
    ON schedule_date_overrides(location_id,member_id,work_date) WHERE kind='working';
CREATE INDEX IF NOT EXISTS idx_schedule_date_org
    ON schedule_date_overrides(organization_id,work_date);
CREATE TABLE IF NOT EXISTS schedule_date_override_intervals (
    id BIGSERIAL PRIMARY KEY,
    override_id BIGINT NOT NULL REFERENCES schedule_date_overrides(id) ON DELETE CASCADE,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CHECK (start_time < end_time)
);
CREATE INDEX IF NOT EXISTS idx_schedule_date_intervals
    ON schedule_date_override_intervals(override_id);
