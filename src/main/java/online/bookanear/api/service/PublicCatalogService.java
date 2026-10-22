package online.bookanear.api.service;

import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** Read-only public catalog and indicative slots. BookingService remains the final authority. */
@Service
public class PublicCatalogService {
    private final NamedParameterJdbcTemplate db;
    public PublicCatalogService(NamedParameterJdbcTemplate db) { this.db = db; }

    private Map<String,Object> organization(UUID publicId) {
        var rows = db.queryForList("""
                SELECT o.id,o.public_id,o.name,o.booking_enabled,
                       COALESCE((c.settings->>'maxAdvanceDays')::integer,180) AS max_advance_days
                FROM organizations o JOIN organization_booking_config c ON c.organization_id=o.id
                WHERE o.public_id=:id AND o.registration_status='active'
                """, Map.of("id", publicId));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");
        return rows.getFirst();
    }

    public Map<String,Object> catalog(UUID publicId, LocalDate date) {
        var org = organization(publicId);
        long id = ((Number)org.get("id")).longValue();
        var locations = db.queryForList("""
                SELECT id,name,address,timezone,latitude,longitude FROM locations
                WHERE organization_id=:org AND active=true AND archived_at IS NULL ORDER BY name
                """, Map.of("org", id));
        var services = db.queryForList("""
                SELECT s.id,s.name,s.description,sv.duration_minutes,sv.slot_interval_minutes,
                       sv.price,sv.currency,sv.duration_mode,sv.max_duration_minutes
                FROM services s JOIN service_versions sv ON sv.service_id=s.id
                WHERE s.organization_id=:org AND s.active=true
                  AND sv.valid_during @> CAST(:day AS date)
                ORDER BY s.name
                """, Map.of("org", id, "day", date));
        var links = db.queryForList("""
                SELECT ls.location_id,ls.service_id FROM location_services ls
                JOIN locations l ON l.id=ls.location_id AND l.organization_id=ls.organization_id
                WHERE ls.organization_id=:org AND ls.active=true AND l.active=true AND l.archived_at IS NULL
                """, Map.of("org", id));
        var reqs = db.queryForList("""
                SELECT r.service_id,r.resource_type_id,r.quantity,r.min_capacity,t.category,t.code AS type_code,t.name AS type_name
                FROM service_resource_requirements r JOIN services s ON s.id=r.service_id
                JOIN resource_types t ON t.id=r.resource_type_id AND t.active=true
                WHERE s.organization_id=:org ORDER BY r.service_id,r.resource_type_id
                """, Map.of("org", id));
        var members = db.queryForList("""
                SELECT m.id,m.display_name,ml.location_id,ws.service_id
                FROM organization_members m
                JOIN member_locations ml ON ml.member_id=m.id AND ml.organization_id=m.organization_id
                JOIN worker_services ws ON ws.member_id=m.id AND ws.organization_id=m.organization_id AND ws.active=true
                WHERE m.organization_id=:org AND m.active=true AND m.archived_at IS NULL ORDER BY m.display_name
                """, Map.of("org", id));
        var catalogServices = new ArrayList<Map<String,Object>>();
        for (var s : services) {
            long sid = ((Number)s.get("id")).longValue();
            var item = new LinkedHashMap<>(s);
            item.put("locationIds", links.stream().filter(x -> ((Number)x.get("service_id")).longValue()==sid)
                    .map(x -> x.get("location_id")).toList());
            item.put("requirements", reqs.stream().filter(x -> ((Number)x.get("service_id")).longValue()==sid).toList());
            catalogServices.add(item);
        }
        return Map.of("id", id, "publicId", publicId.toString(), "name", org.get("name"),
                "bookingEnabled", org.get("booking_enabled"), "locations", locations,
                "services", catalogServices, "members", members);
    }

    public Map<String,Object> slots(UUID publicId, long locationId, long serviceId,
                                     LocalDate date, Integer partySize, Integer quantity) {
        var org = organization(publicId);
        long orgId = ((Number)org.get("id")).longValue();
        if (!Boolean.TRUE.equals(org.get("booking_enabled")))
            throw new ApiException(HttpStatus.CONFLICT, "Bookings are disabled");
        int organizationAdvance = ((Number)org.get("max_advance_days")).intValue();
        if (date.isBefore(LocalDate.now().minusDays(1)) || date.isAfter(LocalDate.now().plusDays(3650)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Date outside supported range");
        if (partySize != null && (partySize < 1 || partySize > 100))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid party size");
        if (quantity != null && (quantity < 1 || quantity > 100))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid quantity");
        var ctx = db.queryForList("""
                SELECT l.timezone,sv.duration_minutes,sv.slot_interval_minutes,
                       sv.buffer_before_minutes,sv.buffer_after_minutes,sv.duration_mode
                FROM locations l JOIN location_services ls ON ls.location_id=l.id AND ls.active
                JOIN services s ON s.id=ls.service_id AND s.organization_id=l.organization_id AND s.active
                JOIN service_versions sv ON sv.service_id=s.id AND sv.valid_during @> CAST(:day AS date)
                WHERE l.id=:loc AND l.organization_id=:org AND l.active AND l.archived_at IS NULL AND s.id=:svc
                """, Map.of("org", orgId,"loc",locationId,"svc",serviceId,"day",date));
        if (ctx.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Service not available at location on this date");
        var version = ctx.getFirst();
        ZoneId zone = ZoneId.of(version.get("timezone").toString());
        int duration = ((Number)version.get("duration_minutes")).intValue();
        int step = ((Number)version.get("slot_interval_minutes")).intValue();
        int before = ((Number)version.get("buffer_before_minutes")).intValue();
        int after = ((Number)version.get("buffer_after_minutes")).intValue();
        boolean custom = "CUSTOM".equals(version.get("duration_mode"));
        if (step < 1 || step > 1440) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid slot interval");
        var params = new MapSqlParameterSource().addValue("org",orgId).addValue("loc",locationId)
                .addValue("day",date).addValue("weekday",date.getDayOfWeek().getValue());
        var intervals = hours(params, "open", null);
        var requirements = db.queryForList("""
                SELECT rr.resource_type_id,rr.quantity,rr.min_capacity,rt.category
                FROM service_resource_requirements rr JOIN resource_types rt ON rt.id=rr.resource_type_id AND rt.active
                WHERE rr.service_id=:svc AND rt.organization_id=:org
                """, Map.of("org",orgId,"svc",serviceId));
        if (quantity != null && requirements.size() != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity requires exactly one resource type");
        var members = db.queryForList("""
                SELECT m.id FROM organization_members m
                JOIN member_locations ml ON ml.member_id=m.id AND ml.location_id=:loc
                JOIN worker_services ws ON ws.member_id=m.id AND ws.service_id=:svc AND ws.active
                WHERE m.organization_id=:org AND m.active AND m.archived_at IS NULL
                """, Map.of("org",orgId,"loc",locationId,"svc",serviceId));
        var policy = db.queryForList("SELECT min_notice_minutes,max_advance_days FROM booking_policies WHERE location_id=:loc",
                Map.of("loc",locationId));
        int notice = policy.isEmpty()?0:((Number)policy.getFirst().get("min_notice_minutes")).intValue();
        int advance = policy.isEmpty() ? organizationAdvance : Math.min(organizationAdvance,
                ((Number)policy.getFirst().get("max_advance_days")).intValue());
        var now = Instant.now();
        var slots = new ArrayList<Map<String,Object>>();
        var seen = new HashSet<String>();
        for (var interval : intervals) {
            LocalTime from = asTime(interval.get("start_time"));
            LocalTime to = asTime(interval.get("end_time"));
            int first = ((from.toSecondOfDay()+step*60-1)/(step*60))*step;
            for (int minute=first; minute<to.toSecondOfDay()/60; minute+=step) {
                LocalTime time = LocalTime.of(minute/60,minute%60);
                if (!custom && minute + duration > to.toSecondOfDay()/60) continue;
                var local = LocalDateTime.of(date,time);
                ZonedDateTime zdt = local.atZone(zone);
                if (!zdt.toLocalDateTime().equals(local)) continue; // DST gap
                Instant start = zdt.toInstant();
                Instant end = start.plus(Duration.ofMinutes(duration));
                if (!start.isAfter(now.plus(Duration.ofMinutes(notice))) ||
                        start.isAfter(now.plus(Duration.ofDays(advance)))) continue;
                if (!custom && unavailable(orgId,locationId,null,start,end)) continue;
                var freeMembers = new ArrayList<Long>();
                for (var member : members) {
                    long mid = ((Number)member.get("id")).longValue();
                    if (memberFree(params,mid,time,time.plusMinutes(duration),start,end,before,after)) freeMembers.add(mid);
                }
                if (requirements.isEmpty() && freeMembers.isEmpty()) continue;
                if (!requirements.isEmpty() && !custom && !resourcesFree(orgId,locationId,requirements,
                        partySize,quantity,start.minus(Duration.ofMinutes(before)),end.plus(Duration.ofMinutes(after)),freeMembers)) continue;
                if (seen.add(time.toString())) {
                    var slot = new LinkedHashMap<String,Object>();
                    slot.put("time",time.toString());
                    slot.put("startsAt",zdt.toOffsetDateTime().toString());
                    slot.put("memberIds",freeMembers);
                    slots.add(slot);
                }
                if (slots.size()>=150) break;
            }
            if (slots.size()>=150) break;
        }
        return Map.of("date",date.toString(),"locationId",locationId,"serviceId",serviceId,
                "durationMode",custom?"CUSTOM":"FIXED", "slots",slots,
                "note",custom?"Return time and equipment availability are checked when booking is submitted":"Availability is rechecked when booking is submitted");
    }

    private static LocalTime asTime(Object value) {
        if (value instanceof LocalTime t) return t;
        if (value instanceof java.sql.Time t) return t.toLocalTime();
        return LocalTime.parse(value.toString());
    }

    private List<Map<String,Object>> hours(MapSqlParameterSource params,String kind,Long member) {
        var p = new MapSqlParameterSource(params.getValues()).addValue("kind",kind).addValue("member",member);
        Boolean dated = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM schedule_date_overrides
                WHERE organization_id=:org AND location_id=:loc AND kind=:kind
                  AND ((CAST(:member AS bigint) IS NULL AND member_id IS NULL) OR member_id=:member)
                  AND work_date=CAST(:day AS date))
                """,p,Boolean.class);
        if (Boolean.TRUE.equals(dated)) return db.queryForList("""
                SELECT i.start_time,i.end_time FROM schedule_date_overrides d
                JOIN schedule_date_override_intervals i ON i.override_id=d.id
                WHERE d.organization_id=:org AND d.location_id=:loc AND d.kind=:kind
                  AND ((CAST(:member AS bigint) IS NULL AND d.member_id IS NULL) OR d.member_id=:member)
                  AND d.work_date=CAST(:day AS date) ORDER BY i.start_time
                """,p);
        return db.queryForList("""
                SELECT start_time,end_time FROM schedule_rules
                WHERE organization_id=:org AND location_id=:loc AND kind=:kind
                  AND ((CAST(:member AS bigint) IS NULL AND member_id IS NULL) OR member_id=:member)
                  AND weekday=:weekday AND effective_dates @> CAST(:day AS date)
                ORDER BY start_time
                """,p);
    }

    private boolean unavailable(long org,long loc,Long member,Instant from,Instant to) {
        var p = new MapSqlParameterSource().addValue("org",org).addValue("loc",loc)
                .addValue("member",member).addValue("from",OffsetDateTime.ofInstant(from,ZoneOffset.UTC))
                .addValue("to",OffsetDateTime.ofInstant(to,ZoneOffset.UTC));
        Long n = db.queryForObject("""
                SELECT count(*) FROM availability_overrides
                WHERE organization_id=:org AND override_type='unavailable'
                  AND (member_id=:member OR (member_id IS NULL AND location_id=:loc))
                  AND period && tstzrange(CAST(:from AS timestamptz),CAST(:to AS timestamptz),'[)')
                """,p,Long.class);
        return n!=null && n>0;
    }

    private boolean memberFree(MapSqlParameterSource p,long member,LocalTime startTime,LocalTime endTime,
                               Instant start,Instant end,int before,int after) {
        if (endTime.isBefore(startTime)) return false;
        var work = hours(p,"working",member);
        if (work.stream().noneMatch(x -> !asTime(x.get("start_time")).isAfter(startTime)
                && !asTime(x.get("end_time")).isBefore(endTime))) return false;
        long org = ((Number)p.getValue("org")).longValue();
        long loc = ((Number)p.getValue("loc")).longValue();
        if (unavailable(org,loc,member,start,end)) return false;
        var args = new MapSqlParameterSource().addValue("member",member)
                .addValue("from",OffsetDateTime.ofInstant(start.minus(Duration.ofMinutes(before)),ZoneOffset.UTC))
                .addValue("to",OffsetDateTime.ofInstant(end.plus(Duration.ofMinutes(after)),ZoneOffset.UTC));
        Long busy = db.queryForObject("""
                SELECT count(*) FROM reservation_items
                WHERE member_id=:member AND item_status='active'
                  AND occupied_period && tstzrange(CAST(:from AS timestamptz),CAST(:to AS timestamptz),'[)')
                """,args,Long.class);
        return busy==null || busy==0;
    }

    private boolean resourcesFree(long org,long loc,List<Map<String,Object>> requirements,Integer partySize,Integer quantity,
                                  Instant from,Instant to,List<Long> freeMembers) {
        for (var req:requirements) {
            String category = req.get("category").toString();
            if (partySize!=null && requirements.stream().noneMatch(r->"space".equals(r.get("category")))) return false;
            int minCapacity = req.get("min_capacity")==null?1:((Number)req.get("min_capacity")).intValue();
            if ("space".equals(category) && partySize!=null) minCapacity=Math.max(minCapacity,partySize);
            var p = new MapSqlParameterSource().addValue("org",org).addValue("loc",loc)
                    .addValue("type",req.get("resource_type_id")).addValue("capacity",minCapacity)
                    .addValue("from",OffsetDateTime.ofInstant(from,ZoneOffset.UTC))
                    .addValue("to",OffsetDateTime.ofInstant(to,ZoneOffset.UTC));
            var available = db.queryForList("""
                    SELECT r.member_id FROM resources r
                    WHERE r.organization_id=:org AND r.location_id=:loc AND r.resource_type_id=:type
                      AND r.active AND r.resource_state='available' AND r.capacity>=:capacity
                      AND NOT EXISTS(SELECT 1 FROM reservation_resources rr
                        WHERE rr.resource_id=r.id AND rr.active
                          AND rr.occupied_period && tstzrange(CAST(:from AS timestamptz),CAST(:to AS timestamptz),'[)'))
                    """,p);
            long count = available.stream().filter(r -> !"staff".equals(category)
                    || (r.get("member_id")!=null && freeMembers.contains(((Number)r.get("member_id")).longValue()))).count();
            int required=((Number)req.get("quantity")).intValue();
            if (quantity!=null) { if(quantity<required) return false; required=quantity; }
            if (count < required) return false;
        }
        return true;
    }
}
