ALTER TABLE locations ADD COLUMN archived_at timestamptz;
ALTER TABLE organization_members ADD COLUMN archived_at timestamptz;

CREATE INDEX locations_active_org_idx ON locations(organization_id, id) WHERE archived_at IS NULL;
CREATE INDEX members_active_org_idx ON organization_members(organization_id, id) WHERE archived_at IS NULL;
