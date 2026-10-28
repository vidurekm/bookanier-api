ALTER TABLE organization_members
    ADD COLUMN provides_services boolean NOT NULL DEFAULT true;
