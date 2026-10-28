INSERT INTO resource_types(organization_id,name,category,code)
SELECT DISTINCT o.id,'Staff member','staff','staff'
FROM organizations o
JOIN organization_booking_config c ON c.organization_id=o.id AND c.booking_model='APPOINTMENT'
JOIN organization_members m ON m.organization_id=o.id
JOIN member_locations ml ON ml.organization_id=m.organization_id AND ml.member_id=m.id
WHERE m.active AND m.provides_services
  AND m.archived_at IS NULL AND m.role<>'admin'
  AND NOT EXISTS (
      SELECT 1 FROM resource_types rt
      WHERE rt.organization_id=o.id AND rt.code='staff'
  )
ON CONFLICT(organization_id,code) DO UPDATE SET category='staff',active=true;

INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,
                      capacity,member_id)
SELECT m.organization_id,ml.location_id,m.display_name,'staff',rt.id,1,m.id
FROM organization_members m
JOIN organization_booking_config c ON c.organization_id=m.organization_id AND c.booking_model='APPOINTMENT'
JOIN member_locations ml ON ml.organization_id=m.organization_id AND ml.member_id=m.id
JOIN resource_types rt ON rt.organization_id=m.organization_id AND rt.code='staff'
WHERE m.active AND m.provides_services AND m.archived_at IS NULL AND m.role<>'admin'
  AND NOT EXISTS (
      SELECT 1 FROM resources r
      JOIN resource_types existing_rt
        ON existing_rt.organization_id=r.organization_id AND existing_rt.id=r.resource_type_id
      WHERE r.organization_id=m.organization_id AND r.location_id=ml.location_id
        AND r.member_id=m.id AND existing_rt.category='staff'
  );
