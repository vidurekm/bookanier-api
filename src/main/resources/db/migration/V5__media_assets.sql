-- Flyway V5: private S3 objects + tenant-scoped metadata and image assignments.
-- Requires V1 (organizations, locations, members, services, resources) and V2 (resource_types).
CREATE TABLE media_assets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    storage_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg','image/png')),
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id)
);
CREATE INDEX media_assets_org_idx ON media_assets (organization_id, created_at DESC);

CREATE TABLE organization_images (
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, media_id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX organization_images_one_primary ON organization_images(organization_id) WHERE is_primary;

CREATE TABLE location_images (
    organization_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, location_id, media_id),
    FOREIGN KEY (organization_id, location_id) REFERENCES locations(organization_id, id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX location_images_one_primary ON location_images(organization_id, location_id) WHERE is_primary;
CREATE INDEX location_images_media_idx ON location_images(organization_id, media_id);

CREATE TABLE service_images (
    organization_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, service_id, media_id),
    FOREIGN KEY (organization_id, service_id) REFERENCES services(organization_id, id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX service_images_one_primary ON service_images(organization_id, service_id) WHERE is_primary;
CREATE INDEX service_images_media_idx ON service_images(organization_id, media_id);

CREATE TABLE resource_images (
    organization_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, resource_id, media_id),
    FOREIGN KEY (organization_id, resource_id) REFERENCES resources(organization_id, id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX resource_images_one_primary ON resource_images(organization_id, resource_id) WHERE is_primary;
CREATE INDEX resource_images_media_idx ON resource_images(organization_id, media_id);

CREATE TABLE member_images (
    organization_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, member_id, media_id),
    FOREIGN KEY (organization_id, member_id) REFERENCES organization_members(organization_id, id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX member_images_one_primary ON member_images(organization_id, member_id) WHERE is_primary;
CREATE INDEX member_images_media_idx ON member_images(organization_id, media_id);

CREATE TABLE resource_type_images (
    organization_id BIGINT NOT NULL,
    resource_type_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, resource_type_id, media_id),
    FOREIGN KEY (organization_id, resource_type_id) REFERENCES resource_types(organization_id, id),
    FOREIGN KEY (organization_id, media_id) REFERENCES media_assets(organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX resource_type_images_one_primary ON resource_type_images(organization_id, resource_type_id) WHERE is_primary;
CREATE INDEX resource_type_images_media_idx ON resource_type_images(organization_id, media_id);

