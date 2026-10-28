package online.bookanear.api;

import online.bookanear.api.service.CatalogAssignmentsService;
import online.bookanear.api.service.StaffResourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class StaffResourceSyncIT extends PostgresIntegrationTest {
    @Autowired StaffResourceService staffResourceService;
    @Autowired CatalogAssignmentsService catalogAssignmentsService;

    private long id(String sql, Map<String, ?> params) {
        return ((Number) db.queryForMap(sql, params).get("id")).longValue();
    }

    @Test
    void synchronizesStaffResourcesWithMemberEligibilityAndLocations() {
        long org = id("INSERT INTO organizations(name) VALUES('Staff resource sync') RETURNING id", Map.of());
        db.update("INSERT INTO organization_booking_config(organization_id,booking_model) VALUES(:org,'APPOINTMENT')",
                Map.of("org", org));
        long firstLocation = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'First branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long secondLocation = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Second branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long member = id("""
                INSERT INTO organization_members(organization_id,display_name,role,provides_services)
                VALUES(:org,'Worker','worker',true) RETURNING id
                """, Map.of("org", org));
        db.update("INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(:org,:member,:loc)",
                Map.of("org", org, "member", member, "loc", firstLocation));

        staffResourceService.synchronizeMember(org, member);
        assertThat(db.queryForList("""
                SELECT location_id FROM resources WHERE organization_id=:org AND member_id=:member
                """, Map.of("org", org, "member", member)))
                .extracting(row -> ((Number) row.get("location_id")).longValue())
                .containsExactly(firstLocation);

        catalogAssignmentsService.setMemberLocations(org, member, List.of(secondLocation));
        assertThat(db.queryForList("""
                SELECT location_id,active FROM resources WHERE organization_id=:org AND member_id=:member
                ORDER BY location_id
                """, Map.of("org", org, "member", member)))
                .extracting(row -> List.of(((Number) row.get("location_id")).longValue(), row.get("active")))
                .containsExactly(List.of(firstLocation, false), List.of(secondLocation, true));

        db.update("UPDATE organization_members SET provides_services=false WHERE organization_id=:org AND id=:member",
                Map.of("org", org, "member", member));
        staffResourceService.synchronizeMember(org, member);
        assertThat(db.queryForObject("""
                SELECT count(*) FROM resources WHERE organization_id=:org AND member_id=:member AND active
                """, Map.of("org", org, "member", member), Integer.class)).isZero();
    }
}
