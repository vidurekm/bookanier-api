-- Flyway V2: univerzalni rezervovatelne zdroje
-- Navazuje na V1__schema.sql z booking-api(4).zip (PostgreSQL 15+).
-- Zachovava puvodni textove resource_type pro kompatibilitu se stavajicim Java kodem.
-- Flyway spousti PostgreSQL migrace v transakci; BEGIN/COMMIT zde nejsou potreba.

-- 1) Katalog typu zdroju v ramci organizace (napr. kadernik, stul, vrtacka).
CREATE TABLE resource_types (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id bigint NOT NULL REFERENCES organizations(id),
    name text NOT NULL,
    category text NOT NULL DEFAULT 'other'
        CHECK (category IN ('staff', 'space', 'equipment', 'other')),
    active boolean NOT NULL DEFAULT true,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, name)
);

-- Typy, ktere uz jsou pouzite ve stavajicich zdrojich nebo pozadavcich sluzeb.
INSERT INTO resource_types (organization_id, name)
SELECT DISTINCT organization_id, resource_type
FROM (
    SELECT organization_id, resource_type FROM resources
    UNION
    SELECT s.organization_id, req.resource_type
    FROM service_resource_requirements req
    JOIN services s ON s.id = req.service_id
) existing_types;

-- 2) Konkretni rezervovatelne zdroje: kapacita stolu, identita kusu naradi,
--    volitelna vazba na pracovnika. Stavajici data zustavaji platna.
ALTER TABLE resources
    ADD COLUMN resource_type_id bigint,
    ADD COLUMN capacity integer NOT NULL DEFAULT 1,
    ADD COLUMN member_id bigint,
    ADD COLUMN inventory_code text,
    ADD COLUMN resource_state text NOT NULL DEFAULT 'available',
    ADD CONSTRAINT resources_capacity_positive CHECK (capacity > 0),
    ADD CONSTRAINT resources_state_valid
        CHECK (resource_state IN ('available', 'maintenance', 'retired')),
    ADD CONSTRAINT resources_type_fk
        FOREIGN KEY (organization_id, resource_type_id)
        REFERENCES resource_types (organization_id, id),
    ADD CONSTRAINT resources_member_fk
        FOREIGN KEY (organization_id, member_id)
        REFERENCES organization_members (organization_id, id);

UPDATE resources r
SET resource_type_id = rt.id
FROM resource_types rt
WHERE rt.organization_id = r.organization_id
  AND rt.name = r.resource_type;

CREATE UNIQUE INDEX resources_inventory_code_uq
    ON resources (organization_id, inventory_code)
    WHERE inventory_code IS NOT NULL;
CREATE INDEX resources_availability_lookup_idx
    ON resources (organization_id, location_id, resource_type_id, capacity)
    WHERE active AND resource_state = 'available';

-- 3) Pozadavky sluzeb: napr. 1 stul s kapacitou >= 4, nebo 2 vrtacky.
--    Puvodni sloupec resource_type a PK zustavaji kvuli zpetne kompatibilite.
ALTER TABLE service_resource_requirements
    ADD COLUMN resource_type_id bigint REFERENCES resource_types(id),
    ADD COLUMN min_capacity integer,
    ADD CONSTRAINT service_resource_requirements_capacity_positive
        CHECK (min_capacity IS NULL OR min_capacity > 0);

UPDATE service_resource_requirements req
SET resource_type_id = rt.id
FROM services s, resource_types rt
WHERE s.id = req.service_id
  AND rt.organization_id = s.organization_id
  AND rt.name = req.resource_type;

CREATE INDEX service_resource_requirements_type_idx
    ON service_resource_requirements (resource_type_id);

-- 4) Parametry konkretni rezervace.
--    Restaurace: party_size; sluzby bez pracovnika: member_id muze byt NULL.
ALTER TABLE reservations
    ADD COLUMN party_size integer,
    ADD CONSTRAINT reservations_party_size_positive
        CHECK (party_size IS NULL OR party_size > 0);

ALTER TABLE reservation_items
    ALTER COLUMN member_id DROP NOT NULL;

-- 5) Snapshot dynamickych pozadavku konkretni polozky rezervace.
--    quantity se muze lisit od vychozi konfigurace sluzby (napr. 2 vrtacky).
--    Pro stul se min_capacity muze odvodit z reservations.party_size.
CREATE TABLE reservation_item_resource_demands (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id bigint NOT NULL,
    location_id bigint NOT NULL,
    reservation_item_id bigint NOT NULL,
    resource_type_id bigint NOT NULL,
    quantity integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
    min_capacity integer CHECK (min_capacity IS NULL OR min_capacity > 0),
    preferred_resource_id bigint,
    FOREIGN KEY (organization_id, location_id, reservation_item_id)
        REFERENCES reservation_items (organization_id, location_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (organization_id, resource_type_id)
        REFERENCES resource_types (organization_id, id),
    FOREIGN KEY (organization_id, location_id, preferred_resource_id)
        REFERENCES resources (organization_id, location_id, id)
);
CREATE INDEX reservation_item_resource_demands_item_idx
    ON reservation_item_resource_demands (reservation_item_id);

-- Existujici reservation_resources a jeho EXCLUDE omezeni proti prekryvu
-- konkretniho zdroje zustavaji beze zmeny.
-- DULEZITE: SQL samo o sobe neimplementuje vyber zdroju, kontrolu poctu kusu,
-- kapacity stolu, cenik vypujcek ani libovolnou delku zapujceni.
-- Pro libovolne dlouhe vypujcky je nutne upravit i trigger
-- validate_reservation_item() z V1, ktery nyni vyzaduje pevnou delku sluzby.
-- Nove resource_type_id jsou zatim NULLABLE pro kompatibilitu se stavajicimi
-- INSERTy v aplikaci. Po prechodu Java kodu na nove sloupce lze zprisnit NOT NULL.
