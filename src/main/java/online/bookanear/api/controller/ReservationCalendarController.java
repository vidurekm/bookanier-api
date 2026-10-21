package online.bookanear.api.controller;

import online.bookanear.api.config.OrganizationSecurity;
import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Appointment-date calendar (the existing reservation search filters created_at). */
@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/calendar")
public class ReservationCalendarController {
    private final NamedParameterJdbcTemplate db;
    private final OrganizationSecurity security;
    public ReservationCalendarController(NamedParameterJdbcTemplate db,OrganizationSecurity security) {
        this.db=db;this.security=security;
    }
    @GetMapping
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public List<Map<String,Object>> calendar(@PathVariable long org,
           @RequestParam LocalDate from,@RequestParam LocalDate to,
           @RequestParam(required=false) Long locationId,Authentication authentication) {
        if (!from.isBefore(to) || to.toEpochDay()-from.toEpochDay()>32)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a range of 1–32 days");
        var p=new MapSqlParameterSource().addValue("org",org).addValue("from",from).addValue("to",to)
                .addValue("loc",locationId);
        String own="";
        if(!security.canManageBookings(org,authentication)) {
            p.addValue("user",security.authenticatedUserId(authentication));
            own="""
                 AND (
                  EXISTS (SELECT 1 FROM organization_members assigned
                          WHERE assigned.organization_id=ri.organization_id AND assigned.id=ri.member_id
                            AND assigned.user_id=:user AND assigned.active=true)
                  OR EXISTS (SELECT 1 FROM reservation_resources rr
                             JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id
                             JOIN organization_members assigned ON assigned.id=res.member_id
                               AND assigned.organization_id=res.organization_id
                             WHERE rr.reservation_item_id=ri.id AND rr.organization_id=ri.organization_id
                               AND assigned.user_id=:user AND assigned.active=true)
                 )
                 """;
        }
        return db.queryForList("""
                SELECT r.id AS reservation_id,r.customer_name,r.status,r.total_amount,r.currency,
                       ri.id AS item_id,ri.starts_at,ri.ends_at,ri.member_id,
                       l.id AS location_id,l.name AS location_name,
                       s.id AS service_id,s.name AS service_name,
                       COALESCE(m.display_name,(
                         SELECT string_agg(DISTINCT assigned.display_name, ', ')
                         FROM reservation_resources rr
                         JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id
                         JOIN organization_members assigned ON assigned.id=res.member_id
                           AND assigned.organization_id=res.organization_id
                         WHERE rr.organization_id=ri.organization_id AND rr.reservation_item_id=ri.id
                       )) AS member_name
                FROM reservation_items ri
                JOIN reservations r ON r.id=ri.reservation_id
                JOIN locations l ON l.id=ri.location_id
                JOIN services s ON s.id=ri.service_id
                LEFT JOIN organization_members m ON m.id=ri.member_id
                WHERE ri.organization_id=:org AND ri.item_status='active'
                  AND (CAST(:loc AS bigint) IS NULL OR ri.location_id=:loc)
                  AND ri.starts_at < (CAST(:to AS date)::timestamp AT TIME ZONE l.timezone)
                  AND ri.ends_at > (CAST(:from AS date)::timestamp AT TIME ZONE l.timezone)
                """ + own + " ORDER BY ri.starts_at,ri.id LIMIT 500",p);
    }
}
