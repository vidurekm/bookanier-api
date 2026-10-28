package online.bookanear.api.controller;

import online.bookanear.api.config.OrganizationSecurity;
import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/reservation-statistics")
public class ReservationStatisticsController {
    private final NamedParameterJdbcTemplate db;
    private final OrganizationSecurity security;

    public ReservationStatisticsController(NamedParameterJdbcTemplate db, OrganizationSecurity security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public List<Map<String, Object>> statistics(@PathVariable long org,
            @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy,
            @RequestParam(required = false) Long locationId, Authentication authentication) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 3660)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a range of at most 10 years");
        if (!List.of("day", "week", "month", "year").contains(groupBy))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid statistics grouping");
        if ("day".equals(groupBy) && ChronoUnit.DAYS.between(from, to) > 365)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Daily statistics are limited to one year");
        if (locationId != null && db.queryForList(
                "SELECT id FROM locations WHERE organization_id=:org AND id=:location",
                Map.of("org", org, "location", locationId)).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "Location not found");

        boolean canManageAllBookings = security.canManageBookings(org, authentication);
        Long userId = canManageAllBookings ? null : security.authenticatedUserId(authentication);
        var params = new MapSqlParameterSource().addValue("org", org).addValue("from", from)
                .addValue("to", to).addValue("groupBy", groupBy).addValue("location", locationId)
                .addValue("user", userId);
        String ownBookingScope = canManageAllBookings ? "" : """
                    AND EXISTS (
                        SELECT 1 FROM reservation_items own_item
                        WHERE own_item.organization_id=r.organization_id
                          AND own_item.reservation_id=r.id
                          AND (
                            EXISTS (SELECT 1 FROM organization_members own_member
                                    WHERE own_member.organization_id=own_item.organization_id
                                      AND own_member.id=own_item.member_id
                                      AND own_member.user_id=:user AND own_member.active=true)
                            OR EXISTS (SELECT 1 FROM reservation_resources rr
                                       JOIN resources staff_resource ON staff_resource.organization_id=rr.organization_id
                                           AND staff_resource.id=rr.resource_id
                                       JOIN organization_members own_member ON own_member.organization_id=staff_resource.organization_id
                                           AND own_member.id=staff_resource.member_id
                                       WHERE rr.organization_id=own_item.organization_id
                                         AND rr.reservation_item_id=own_item.id
                                         AND rr.active
                                         AND own_member.user_id=:user AND own_member.active=true)
                          )
                    )
                """;
        var rows = db.queryForList("""
                WITH periods AS (
                    SELECT generate_series(
                        date_trunc(:groupBy, CAST(:from AS timestamp)),
                        date_trunc(:groupBy, CAST(:to AS timestamp)),
                        CASE :groupBy
                            WHEN 'day' THEN interval '1 day'
                            WHEN 'week' THEN interval '1 week'
                            WHEN 'month' THEN interval '1 month'
                            ELSE interval '1 year'
                        END
                    )::date AS period_start
                ), completed AS (
                    SELECT r.id, r.total_amount, r.currency, r.party_size,
                           (MIN(ri.starts_at) AT TIME ZONE l.timezone)::date AS local_date
                    FROM reservations r
                    JOIN reservation_items ri ON ri.organization_id=r.organization_id
                        AND ri.reservation_id=r.id
                    JOIN locations l ON l.organization_id=r.organization_id AND l.id=r.location_id
                    WHERE r.organization_id=:org AND r.status='completed'
                      AND (CAST(:location AS bigint) IS NULL OR r.location_id=:location)
                """ + ownBookingScope + """
                    GROUP BY r.id, r.total_amount, r.currency, r.party_size, l.timezone
                ), booking_metrics AS (
                    SELECT date_trunc(:groupBy, local_date::timestamp)::date AS period_start,
                           COUNT(*) AS reservation_count
                    FROM completed
                    WHERE local_date BETWEEN :from AND :to
                    GROUP BY 1
                ), completed_work AS (
                    SELECT r.id AS reservation_id, ri.duration_minutes,
                           (ri.starts_at AT TIME ZONE l.timezone)::date AS local_date,
                           assigned.member_id
                    FROM reservations r
                    JOIN reservation_items ri ON ri.organization_id=r.organization_id
                        AND ri.reservation_id=r.id AND ri.item_status='active'
                    JOIN locations l ON l.organization_id=r.organization_id AND l.id=r.location_id
                    CROSS JOIN LATERAL (
                        SELECT ri.member_id AS member_id WHERE ri.member_id IS NOT NULL
                        UNION
                        SELECT staff_resource.member_id
                        FROM reservation_resources rr
                        JOIN resources staff_resource ON staff_resource.organization_id=rr.organization_id
                            AND staff_resource.id=rr.resource_id AND staff_resource.member_id IS NOT NULL
                        JOIN resource_types staff_type ON staff_type.organization_id=staff_resource.organization_id
                            AND staff_type.id=staff_resource.resource_type_id AND staff_type.category='staff'
                        WHERE rr.organization_id=ri.organization_id
                          AND rr.reservation_item_id=ri.id AND rr.active
                    ) assigned
                    WHERE r.organization_id=:org AND r.status='completed'
                      AND (CAST(:location AS bigint) IS NULL OR r.location_id=:location)
                      AND (CAST(:user AS bigint) IS NULL OR EXISTS (
                          SELECT 1 FROM organization_members assigned_member
                          WHERE assigned_member.organization_id=r.organization_id
                            AND assigned_member.id=assigned.member_id
                            AND assigned_member.user_id=:user AND assigned_member.active=true
                      ))
                """ + ownBookingScope + """
                ), work_metrics AS (
                    SELECT date_trunc(:groupBy, local_date::timestamp)::date AS period_start,
                           SUM(duration_minutes) AS used_capacity
                    FROM completed_work
                    WHERE local_date BETWEEN :from AND :to
                    GROUP BY 1
                ), revenue_metrics AS (
                    SELECT date_trunc(:groupBy, local_date::timestamp)::date AS period_start,
                           currency, SUM(total_amount) AS revenue
                    FROM completed
                    WHERE local_date BETWEEN :from AND :to
                    GROUP BY 1, currency
                ), days AS (
                    SELECT generate_series(CAST(:from AS date)::timestamp,
                                           CAST(:to AS date)::timestamp,
                                           interval '1 day')::date AS work_date
                ), opening_intervals AS (
                    SELECT d.work_date,sr.location_id,
                           EXTRACT(EPOCH FROM sr.start_time)/60 AS start_minute,
                           EXTRACT(EPOCH FROM sr.end_time)/60 AS end_minute
                    FROM days d
                    JOIN schedule_rules sr ON sr.organization_id=:org AND sr.kind='open'
                        AND sr.weekday=EXTRACT(ISODOW FROM d.work_date)::smallint
                        AND sr.effective_dates @> d.work_date
                    JOIN locations loc ON loc.organization_id=sr.organization_id
                        AND loc.id=sr.location_id AND loc.active AND loc.archived_at IS NULL
                    WHERE (CAST(:location AS bigint) IS NULL OR sr.location_id=:location)
                      AND NOT EXISTS (
                          SELECT 1 FROM schedule_date_overrides ov
                          WHERE ov.organization_id=sr.organization_id
                            AND ov.location_id=sr.location_id AND ov.member_id IS NULL
                            AND ov.kind='open' AND ov.work_date=d.work_date
                      )
                    UNION ALL
                    SELECT d.work_date,ov.location_id,
                           EXTRACT(EPOCH FROM iv.start_time)/60,
                           EXTRACT(EPOCH FROM iv.end_time)/60
                    FROM days d
                    JOIN schedule_date_overrides ov ON ov.organization_id=:org
                        AND ov.kind='open' AND ov.member_id IS NULL AND ov.work_date=d.work_date
                    JOIN schedule_date_override_intervals iv ON iv.override_id=ov.id
                    JOIN locations loc ON loc.organization_id=ov.organization_id
                        AND loc.id=ov.location_id AND loc.active AND loc.archived_at IS NULL
                    WHERE (CAST(:location AS bigint) IS NULL OR ov.location_id=:location)
                ), member_intervals AS (
                    SELECT d.work_date,sr.location_id,sr.member_id,
                           EXTRACT(EPOCH FROM sr.start_time)/60 AS start_minute,
                           EXTRACT(EPOCH FROM sr.end_time)/60 AS end_minute
                    FROM days d
                    JOIN schedule_rules sr ON sr.organization_id=:org AND sr.kind='working'
                        AND sr.weekday=EXTRACT(ISODOW FROM d.work_date)::smallint
                        AND sr.effective_dates @> d.work_date
                    JOIN organization_members m ON m.organization_id=sr.organization_id
                        AND m.id=sr.member_id AND m.active AND m.provides_services
                        AND m.archived_at IS NULL
                    JOIN member_locations ml ON ml.organization_id=m.organization_id
                        AND ml.member_id=m.id AND ml.location_id=sr.location_id
                    WHERE (CAST(:location AS bigint) IS NULL OR sr.location_id=:location)
                      AND (CAST(:user AS bigint) IS NULL OR m.user_id=:user)
                      AND EXISTS (
                          SELECT 1 FROM worker_services ws
                          JOIN services s ON s.organization_id=ws.organization_id
                              AND s.id=ws.service_id AND s.active
                          JOIN location_services ls ON ls.organization_id=ws.organization_id
                              AND ls.service_id=ws.service_id AND ls.location_id=sr.location_id
                              AND ls.active
                          JOIN service_versions sv ON sv.service_id=s.id
                              AND sv.valid_during @> d.work_date
                          WHERE ws.organization_id=m.organization_id AND ws.member_id=m.id
                            AND ws.active
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM schedule_date_overrides ov
                          WHERE ov.organization_id=sr.organization_id
                            AND ov.location_id=sr.location_id AND ov.member_id=sr.member_id
                            AND ov.kind='working' AND ov.work_date=d.work_date
                      )
                    UNION ALL
                    SELECT d.work_date,ov.location_id,ov.member_id,
                           EXTRACT(EPOCH FROM iv.start_time)/60,
                           EXTRACT(EPOCH FROM iv.end_time)/60
                    FROM days d
                    JOIN schedule_date_overrides ov ON ov.organization_id=:org
                        AND ov.kind='working' AND ov.work_date=d.work_date
                    JOIN organization_members m ON m.organization_id=ov.organization_id
                        AND m.id=ov.member_id AND m.active AND m.provides_services
                        AND m.archived_at IS NULL
                    JOIN member_locations ml ON ml.organization_id=m.organization_id
                        AND ml.member_id=m.id AND ml.location_id=ov.location_id
                    JOIN schedule_date_override_intervals iv ON iv.override_id=ov.id
                    WHERE (CAST(:location AS bigint) IS NULL OR ov.location_id=:location)
                      AND (CAST(:user AS bigint) IS NULL OR m.user_id=:user)
                      AND EXISTS (
                          SELECT 1 FROM worker_services ws
                          JOIN services s ON s.organization_id=ws.organization_id
                              AND s.id=ws.service_id AND s.active
                          JOIN location_services ls ON ls.organization_id=ws.organization_id
                              AND ls.service_id=ws.service_id AND ls.location_id=ov.location_id
                              AND ls.active
                          JOIN service_versions sv ON sv.service_id=s.id
                              AND sv.valid_during @> d.work_date
                          WHERE ws.organization_id=m.organization_id AND ws.member_id=m.id
                            AND ws.active
                      )
                ), available_work AS (
                    SELECT mi.work_date,mi.member_id,
                           GREATEST(mi.start_minute,oi.start_minute) AS start_minute,
                           LEAST(mi.end_minute,oi.end_minute) AS end_minute
                    FROM member_intervals mi
                    JOIN opening_intervals oi ON oi.work_date=mi.work_date
                        AND oi.location_id=mi.location_id
                    WHERE GREATEST(mi.start_minute,oi.start_minute)
                          < LEAST(mi.end_minute,oi.end_minute)
                ), capacity_boundaries AS (
                    SELECT work_date,member_id,start_minute AS boundary FROM available_work
                    UNION
                    SELECT work_date,member_id,end_minute FROM available_work
                ), capacity_segments AS (
                    SELECT work_date,member_id,boundary AS start_minute,
                           LEAD(boundary) OVER(PARTITION BY work_date,member_id ORDER BY boundary) AS end_minute
                    FROM capacity_boundaries
                ), capacity_metrics AS (
                    SELECT date_trunc(:groupBy,seg.work_date::timestamp)::date AS period_start,
                           SUM(seg.end_minute-seg.start_minute) AS capacity
                    FROM capacity_segments seg
                    WHERE seg.end_minute IS NOT NULL
                      AND EXISTS (
                          SELECT 1 FROM available_work aw
                          WHERE aw.work_date=seg.work_date AND aw.member_id=seg.member_id
                            AND aw.start_minute<=seg.start_minute
                            AND aw.end_minute>=seg.end_minute
                      )
                    GROUP BY 1
                )
                SELECT p.period_start,
                       COALESCE(b.reservation_count, 0) AS reservation_count,
                       COALESCE(cm.capacity,0) AS capacity,
                       COALESCE(wm.used_capacity,0) AS used_capacity,
                       rm.currency, rm.revenue
                FROM periods p
                LEFT JOIN booking_metrics b ON b.period_start=p.period_start
                LEFT JOIN work_metrics wm ON wm.period_start=p.period_start
                LEFT JOIN capacity_metrics cm ON cm.period_start=p.period_start
                LEFT JOIN revenue_metrics rm ON rm.period_start=p.period_start
                ORDER BY p.period_start, rm.currency
                """, params);

        var result = new LinkedHashMap<LocalDate, Map<String, Object>>();
        for (var row : rows) {
            LocalDate period = ((java.sql.Date) row.get("period_start")).toLocalDate();
            @SuppressWarnings("unchecked")
            var point = result.computeIfAbsent(period, key -> {
                var values = new LinkedHashMap<String, Object>();
                values.put("period_start", key);
                values.put("reservation_count", row.get("reservation_count"));
                values.put("capacity", row.get("capacity"));
                values.put("used_capacity", row.get("used_capacity"));
                values.put("revenue", new ArrayList<Map<String, Object>>());
                return values;
            });
            if (row.get("currency") != null) {
                @SuppressWarnings("unchecked")
                var revenues = (List<Map<String, Object>>) point.get("revenue");
                revenues.add(Map.of("currency", row.get("currency"), "amount", row.get("revenue")));
            }
        }
        return new ArrayList<>(result.values());
    }
}
