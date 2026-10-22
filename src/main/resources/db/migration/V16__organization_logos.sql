CREATE TABLE organization_logos (
    organization_id BIGINT PRIMARY KEY REFERENCES organizations(id),
    media_id BIGINT NOT NULL UNIQUE,
    FOREIGN KEY (organization_id, media_id)
        REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
