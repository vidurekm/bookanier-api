-- Seznam rezervaci a kalendar spojuji polozky a zdroje po rezervaci/polozce; FK sloupce nemely index.
CREATE INDEX IF NOT EXISTS reservation_items_reservation_idx
    ON reservation_items(organization_id, reservation_id);
CREATE INDEX IF NOT EXISTS reservation_items_member_starts_idx
    ON reservation_items(organization_id, member_id, starts_at);
CREATE INDEX IF NOT EXISTS reservation_resources_item_idx
    ON reservation_resources(organization_id, reservation_item_id, resource_id);
CREATE INDEX IF NOT EXISTS reservation_resources_resource_idx
    ON reservation_resources(organization_id, resource_id);
CREATE INDEX IF NOT EXISTS reservations_org_created_idx
    ON reservations(organization_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS reservations_org_location_status_idx
    ON reservations(organization_id, location_id, status);
