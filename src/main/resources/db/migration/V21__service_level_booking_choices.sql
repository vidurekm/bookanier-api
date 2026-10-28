ALTER TABLE services
    ADD COLUMN customer_can_choose_staff boolean NOT NULL DEFAULT false,
    ADD COLUMN customer_can_choose_resource boolean NOT NULL DEFAULT false;

UPDATE services s
SET customer_can_choose_staff =
        COALESCE((c.settings->>'showStaffChoice')::boolean, true),
    customer_can_choose_resource =
        COALESCE((c.settings->>'customerSelectResource')::boolean, false)
FROM organization_booking_config c
WHERE c.organization_id=s.organization_id;

UPDATE services s
SET customer_can_choose_resource=false
WHERE s.customer_can_choose_resource
  AND ((SELECT count(*) FROM service_resource_requirements req
        JOIN resource_types rt ON rt.id=req.resource_type_id
        WHERE req.service_id=s.id AND rt.category<>'staff' AND rt.active)<>1
       OR EXISTS(SELECT 1 FROM service_resource_requirements req
        JOIN resource_types rt ON rt.id=req.resource_type_id
        WHERE req.service_id=s.id AND rt.category<>'staff' AND rt.active AND req.quantity<>1));
