package online.bookanear.api.service;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class StaffResourceService {
    private final NamedParameterJdbcTemplate db;

    public StaffResourceService(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void synchronizeMember(long organizationId, long memberId) {
        var members = db.queryForList("""
                SELECT m.display_name,m.role,m.active,m.provides_services,m.archived_at,c.booking_model
                FROM organization_members m
                JOIN organization_booking_config c ON c.organization_id=m.organization_id
                WHERE m.organization_id=:org AND m.id=:member
                FOR UPDATE OF m
                """, Map.of("org", organizationId, "member", memberId));
        if (members.isEmpty()) return;

        var member = members.getFirst();
        boolean eligible = "APPOINTMENT".equals(member.get("booking_model"))
                && Boolean.TRUE.equals(member.get("active"))
                && Boolean.TRUE.equals(member.get("provides_services"))
                && member.get("archived_at") == null
                && !"admin".equals(member.get("role"));

        Long staffTypeId = null;
        if (eligible) {
            staffTypeId = db.queryForObject("""
                    INSERT INTO resource_types(organization_id,name,category,code)
                    VALUES(:org,'Staff member','staff','staff')
                    ON CONFLICT(organization_id,code) DO UPDATE SET category='staff',active=true
                    RETURNING id
                    """, Map.of("org", organizationId), Long.class);
        }

        db.update("""
                UPDATE resources r SET active=false
                FROM resource_types rt
                WHERE r.organization_id=:org AND r.member_id=:member
                  AND rt.organization_id=r.organization_id AND rt.id=r.resource_type_id
                  AND rt.category='staff'
                  AND (:eligible=false OR NOT EXISTS (
                      SELECT 1 FROM member_locations ml
                      WHERE ml.organization_id=:org AND ml.member_id=:member
                        AND ml.location_id=r.location_id))
                """, Map.of("org", organizationId, "member", memberId, "eligible", eligible));

        if (!eligible) return;

        db.update("""
                UPDATE resources r SET name=:name,active=true
                FROM resource_types rt, member_locations ml
                WHERE r.organization_id=:org AND r.member_id=:member
                  AND rt.organization_id=r.organization_id AND rt.id=r.resource_type_id
                  AND rt.category='staff'
                  AND ml.organization_id=:org AND ml.member_id=:member
                  AND ml.location_id=r.location_id
                """, Map.of("org", organizationId, "member", memberId,
                "name", member.get("display_name")));

        db.update("""
                INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,
                                     capacity,member_id)
                SELECT :org,ml.location_id,:name,'staff',:type,1,:member
                FROM member_locations ml
                WHERE ml.organization_id=:org AND ml.member_id=:member
                  AND NOT EXISTS (
                      SELECT 1 FROM resources r JOIN resource_types rt
                        ON rt.organization_id=r.organization_id AND rt.id=r.resource_type_id
                      WHERE r.organization_id=:org AND r.location_id=ml.location_id
                        AND r.member_id=:member AND rt.category='staff')
                """, Map.of("org", organizationId, "member", memberId,
                "name", member.get("display_name"), "type", staffTypeId));
    }
}
