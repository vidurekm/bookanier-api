package online.bookanear.api.controller;

import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/organizations/{publicId}")
public class PublicModeController {
    private final NamedParameterJdbcTemplate db;
    public PublicModeController(NamedParameterJdbcTemplate db) { this.db=db; }
    @GetMapping("/resources")
    public Object resources(@PathVariable UUID publicId,@RequestParam long locationId,@RequestParam long serviceId) {
        var org=db.queryForList("SELECT o.id FROM organizations o JOIN organization_booking_config c ON c.organization_id=o.id WHERE o.public_id=:id AND o.registration_status='active' AND c.setup_status='complete' AND c.settings->>'customerSelectResource'='true'",Map.of("id",publicId));
        if(org.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Organization not found");
        return db.queryForList("""
            SELECT r.id,r.name,r.capacity FROM resources r
            JOIN service_resource_requirements req ON req.resource_type_id=r.resource_type_id
            JOIN services s ON s.id=req.service_id
            JOIN locations l ON l.id=r.location_id AND l.organization_id=r.organization_id
              AND l.active AND l.archived_at IS NULL
            JOIN resource_types rt ON rt.id=r.resource_type_id AND rt.category <> 'staff'
            WHERE r.organization_id=:org AND r.location_id=:loc AND req.service_id=:service
              AND s.organization_id=:org AND r.active AND r.resource_state='available'
            ORDER BY r.name
            """,Map.of("org",org.getFirst().get("id"),"loc",locationId,"service",serviceId));
    }
    @GetMapping("/booking-mode")
    public Object mode(@PathVariable UUID publicId) {
        var rows=db.queryForList("""
            SELECT c.booking_model,c.template_key,c.setup_status,jsonb_build_object(
                      'customerSelectResource',COALESCE((c.settings->>'customerSelectResource')::boolean,false),
                      'showStaffChoice',COALESCE((c.settings->>'showStaffChoice')::boolean,true),
                      'defaultPublicLanguage',COALESCE(c.settings->>'defaultPublicLanguage','cs'),
                      'defaultCurrency',COALESCE(c.settings->>'defaultCurrency','CZK'),
                      'maxAdvanceDays',COALESCE((c.settings->>'maxAdvanceDays')::integer,180),
                      'multiLocation',COALESCE((c.settings->>'multiLocation')::boolean,false)
                   )::text AS settings,
                   (SELECT count(*) FROM locations l WHERE l.organization_id=o.id AND l.active AND l.archived_at IS NULL) AS location_count
            FROM organizations o JOIN organization_booking_config c ON c.organization_id=o.id
            WHERE o.public_id=:id AND o.registration_status='active'
            """,Map.of("id",publicId));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Organization not found");
        return rows.getFirst();
    }
}
