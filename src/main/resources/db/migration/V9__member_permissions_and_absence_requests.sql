-- Additive permissions; existing workers remain restricted to their own bookings.
ALTER TABLE organization_members
    ADD COLUMN IF NOT EXISTS can_manage_bookings boolean NOT NULL DEFAULT false;
ALTER TABLE organization_members
    ADD COLUMN IF NOT EXISTS can_approve_own_absence boolean NOT NULL DEFAULT false;

-- A request does not alter availability until it is approved and applied.
-- Time intervals are stored as JSONB to preserve the complete requested day.
CREATE TABLE IF NOT EXISTS member_absence_requests (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id bigint NOT NULL REFERENCES organizations(id),
    member_id bigint NOT NULL,
    location_id bigint NOT NULL,
    work_date date NOT NULL,
    requested_intervals jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason text,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','approved','rejected')),
    requested_by bigint NOT NULL REFERENCES users(id),
    decided_by bigint REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,member_id) REFERENCES organization_members(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES locations(organization_id,id),
    CHECK (jsonb_typeof(requested_intervals) = 'array'),
    CHECK (char_length(coalesce(reason,'')) <= 1000)
);
CREATE INDEX IF NOT EXISTS member_absence_requests_org_status_idx
    ON member_absence_requests(organization_id,status,work_date);
CREATE INDEX IF NOT EXISTS member_absence_requests_member_idx
    ON member_absence_requests(organization_id,member_id,work_date);
