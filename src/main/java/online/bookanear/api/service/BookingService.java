package online.bookanear.api.service;

import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
public class BookingService {
    private final NamedParameterJdbcTemplate db;
    private final String baseUrl;
    private final ResourceAllocationService allocator;
    private final SecureRandom random = new SecureRandom();

    BookingService(NamedParameterJdbcTemplate db, @Value("${app.public-base-url}") String baseUrl, ResourceAllocationService allocator) {
        this.db = db;
        this.baseUrl = baseUrl;
        this.allocator = allocator;
    }

    private Map<String, Object> one(String sql, Map<String, ?> p) {
        try {
            return db.queryForMap(sql, p);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    private void ensure(boolean condition, String msg) {
        if (!condition) throw new ApiException(HttpStatus.CONFLICT, msg);
    }

    private static OffsetDateTime utc(OffsetDateTime dt) {
        return dt.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Version(long id, int duration, int slot, int before, int after, String currency, java.math.BigDecimal price, String durationMode, Integer maxDuration) {
    }

    private Version version(long service, long location, OffsetDateTime starts) {
        var row = one("select sv.id,sv.duration_minutes,sv.slot_interval_minutes,sv.buffer_before_minutes,sv.buffer_after_minutes,sv.price,sv.currency,sv.duration_mode,sv.max_duration_minutes from service_versions sv join locations l on l.id=:loc where sv.service_id=:svc and sv.valid_during @> ((cast(:at as timestamptz) at time zone l.timezone)::date)", Map.of("loc", location, "svc", service, "at", starts));
        return new Version(((Number) row.get("id")).longValue(), ((Number) row.get("duration_minutes")).intValue(), ((Number) row.get("slot_interval_minutes")).intValue(), ((Number) row.get("buffer_before_minutes")).intValue(), ((Number) row.get("buffer_after_minutes")).intValue(), row.get("currency").toString(), (java.math.BigDecimal) row.get("price"), row.get("duration_mode").toString(), row.get("max_duration_minutes") == null ? null : ((Number) row.get("max_duration_minutes")).intValue());
    }

    private Map<String, Object> context(long org, long loc, long member, long service) {
        var rows = db.queryForList("select l.timezone from organizations o join locations l on l.organization_id=o.id and l.id=:loc join organization_members m on m.organization_id=o.id and m.id=:member join member_locations ml on ml.member_id=m.id and ml.location_id=l.id join services s on s.organization_id=o.id and s.id=:svc join worker_services ws on ws.member_id=m.id and ws.service_id=s.id and ws.active join location_services ls on ls.location_id=l.id and ls.service_id=s.id and ls.active where o.id=:org and o.booking_enabled and l.active and m.active and s.active", Map.of("org", org, "loc", loc, "member", member, "svc", service));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Service/member/location not configured");
        return rows.getFirst();
    }

    private void lock(long org, long member) {
        db.queryForList("select id from organizations where id=:id for update", Map.of("id", org));
        db.queryForList("select id from organization_members where id=:id and organization_id=:org for update", Map.of("id", member, "org", org));
    }

    private void checkAvailability(long org, long loc, long member, long service, OffsetDateTime start, Version v, Long excludeItem) {
        var zone = ZoneId.of(context(org, loc, member, service).get("timezone").toString());
        var local = start.atZoneSameInstant(zone);
        var end = start.plusMinutes(v.duration());
        ensure(start.isAfter(OffsetDateTime.now(ZoneOffset.UTC)), "Start must be in the future");
        ensure(local.getMinute() % v.slot() == 0 && local.getSecond() == 0 && local.getNano() == 0 || (local.toLocalTime().toSecondOfDay() % (v.slot() * 60) == 0), "Start is not on a slot boundary");
        var p = new HashMap<String, Object>();
        p.put("org", org);
        p.put("loc", loc);
        p.put("member", member);
        p.put("start", start);
        p.put("end", end);
        p.put("weekday", local.getDayOfWeek().getValue());
        p.put("day", local.toLocalDate());
        p.put("st", local.toLocalTime());
        p.put("et", end.atZoneSameInstant(zone).toLocalTime());
        ensure(local.toLocalDate().equals(end.atZoneSameInstant(zone).toLocalDate()), "Cross-midnight services not supported in starter");
        var count = db.queryForObject("select count(*) from schedule_rules w where w.organization_id=:org and w.location_id=:loc and w.member_id=:member and w.kind='working' and w.weekday=:weekday and w.effective_dates @> cast(:day as date) and w.start_time<=cast(:st as time) and w.end_time>=cast(:et as time)", p, Long.class);
        var datedWorker = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM schedule_date_overrides d
                  WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id=:member
                    AND d.kind='working' AND d.work_date=CAST(:day AS date))
                """,p,Boolean.class);
        if (Boolean.TRUE.equals(datedWorker)) {
            count = db.queryForObject("""
                    SELECT count(*) FROM schedule_date_overrides d
                    JOIN schedule_date_override_intervals i ON i.override_id=d.id
                    WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id=:member
                      AND d.kind='working' AND d.work_date=CAST(:day AS date)
                      AND i.start_time<=CAST(:st AS time) AND i.end_time>=CAST(:et AS time)
                    """,p,Long.class);
        }
        ensure(count != null && count > 0, "Outside working hours");
        var open = db.queryForObject("select count(*) from schedule_rules w where w.organization_id=:org and w.location_id=:loc and w.kind='open' and w.weekday=:weekday and w.effective_dates @> cast(:day as date) and w.start_time<=cast(:st as time) and w.end_time>=cast(:et as time)", p, Long.class);
        var datedOpen = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM schedule_date_overrides d
                  WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id IS NULL
                    AND d.kind='open' AND d.work_date=CAST(:day AS date))
                """,p,Boolean.class);
        if (Boolean.TRUE.equals(datedOpen)) {
            open = db.queryForObject("""
                    SELECT count(*) FROM schedule_date_overrides d
                    JOIN schedule_date_override_intervals i ON i.override_id=d.id
                    WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id IS NULL
                      AND d.kind='open' AND d.work_date=CAST(:day AS date)
                      AND i.start_time<=CAST(:st AS time) AND i.end_time>=CAST(:et AS time)
                    """,p,Long.class);
        }
        ensure(open != null && open > 0, "Location is closed");
        var blocked = db.queryForObject("select count(*) from availability_overrides where organization_id=:org and override_type='unavailable' and (member_id=:member or (member_id is null and location_id=:loc)) and period && tstzrange(cast(:start as timestamptz),cast(:end as timestamptz),'[)')", p, Long.class);
        ensure(blocked != null && blocked == 0, "Worker or location unavailable");
        p.put("occStart", start.minusMinutes(v.before()));
        p.put("occEnd", end.plusMinutes(v.after()));
        p.put("exclude", excludeItem == null ? -1L : excludeItem);
        var overlaps = db.queryForObject("select count(*) from reservation_items where member_id=:member and item_status='active' and id<>:exclude and occupied_period && tstzrange(cast(:occStart as timestamptz),cast(:occEnd as timestamptz),'[)')", p, Long.class);
        ensure(overlaps != null && overlaps == 0, "Slot occupied");
        var policy = db.queryForList("select min_notice_minutes,max_advance_days from booking_policies where location_id=:loc", Map.of("loc", loc));
        if (!policy.isEmpty()) {
            var policyRow = policy.getFirst();
            ensure(!start.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(((Number) policyRow.get("min_notice_minutes")).longValue())), "Too little notice");
            ensure(!start.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(((Number) policyRow.get("max_advance_days")).longValue())), "Too far in advance");
        }
    }

    private Map<String, Object> tokenRow(String token, boolean lock) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,}")) throw new ApiException(HttpStatus.NOT_FOUND, "Invalid link");
        var rows = db.queryForList("select r.*,t.id as token_id,t.scope from reservation_tokens t join reservations r on r.id=t.reservation_id where encode(t.token_hash,'hex')=:hash and t.revoked_at is null and t.expires_at>now()" + (lock ? " for update of r,t" : ""), Map.of("hash", digest(token)));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Link expired or invalid");
        return rows.getFirst();
    }

    private String createToken(long reservationId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("insert into reservation_tokens(reservation_id,token_hash,expires_at) values(:id,decode(:hash,'hex'),now()+interval '365 days')", Map.of("id", reservationId, "hash", digest(token)));
        return token;
    }

    @Transactional
    public Map<String, Object> create(BookingDto.CreateBooking r) {
        var start = utc(r.startsAt());
        lock(r.organizationId(), r.memberId());
        Version v = version(r.serviceId(), r.locationId(), start);
        checkAvailability(r.organizationId(), r.locationId(), r.memberId(), r.serviceId(), start, v, null);
        var args = new MapSqlParameterSource().addValue("org", r.organizationId()).addValue("loc", r.locationId()).addValue("name", r.customerName()).addValue("email", r.customerEmail()).addValue("phone", r.customerPhone()).addValue("price", v.price()).addValue("currency", v.currency());
        long id = ((Number) db.queryForMap("insert into reservations(organization_id,location_id,customer_name,customer_email,customer_phone,status,total_amount,currency) values(:org,:loc,:name,:email,:phone,'confirmed',:price,:currency) returning id", args).get("id")).longValue();
        insertItem(id, r.organizationId(), r.locationId(), r.serviceId(), r.memberId(), start, v);
        String token = createToken(id);
        db.update("insert into reservation_events(reservation_id,event_type) values(:id,'created')", Map.of("id", id));
        return Map.of("id", id, "status", "confirmed", "startsAt", start, "endsAt", start.plusMinutes(v.duration()), "price", v.price(), "currency", v.currency(), "manageUrl", baseUrl + "/sprava/" + token);
    }

    /** Public universal booking; serializes allocations by locking the tenant row. */
    @Transactional
    public Map<String, Object> createUniversal(BookingDto.CreateUniversalBooking r) {
        OffsetDateTime start = utc(r.startsAt());
        // Same lock order as legacy create() and backoffice schedule edits.
        db.queryForList("SELECT id FROM organizations WHERE id=:org FOR UPDATE", Map.of("org", r.organizationId()));
        Version v = version(r.serviceId(), r.locationId(), start);
        OffsetDateTime end;
        if ("CUSTOM".equals(v.durationMode())) {
            if (r.endsAt() == null)
                throw new ApiException(HttpStatus.BAD_REQUEST, "endsAt is required for CUSTOM duration");
            end = utc(r.endsAt());
        } else {
            if (r.endsAt() != null && !utc(r.endsAt()).isEqual(start.plusMinutes(v.duration())))
                throw new ApiException(HttpStatus.BAD_REQUEST, "endsAt must match fixed service duration");
            end = start.plusMinutes(v.duration());
        }
        long minutes = java.time.Duration.between(start, end).toMinutes();
        if (!end.isAfter(start) || !start.plusMinutes(minutes).isEqual(end)
                || minutes < v.duration() || minutes > Integer.MAX_VALUE
                || (v.maxDuration() != null && minutes > v.maxDuration()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid reservation duration");
        if ("FIXED".equals(v.durationMode()) && minutes != v.duration())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Fixed duration mismatch");

        var context = db.queryForList("""
                SELECT l.timezone FROM organizations o
                JOIN locations l ON l.organization_id=o.id AND l.id=:loc AND l.active
                JOIN services s ON s.organization_id=o.id AND s.id=:service AND s.active
                JOIN location_services ls ON ls.location_id=l.id AND ls.service_id=s.id AND ls.active
                WHERE o.id=:org AND o.booking_enabled AND o.registration_status='active'
                """, Map.of("org", r.organizationId(), "loc", r.locationId(), "service", r.serviceId()));
        if (context.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service/location is not available");
        ZoneId zone = ZoneId.of(context.getFirst().get("timezone").toString());
        checkLocationAvailability(r.organizationId(), r.locationId(), start, end, v, zone);
        OffsetDateTime occupiedFrom = start.minusMinutes(v.before());
        OffsetDateTime occupiedTo = end.plusMinutes(v.after());
        // A staff member is still checked against legacy worker bookings and working hours.
        var allocated = allocator.allocate(r.organizationId(), r.locationId(), r.serviceId(),
                occupiedFrom, occupiedTo, r, memberId -> {
                    if (!"FIXED".equals(v.durationMode())) return false;
                    try {
                        checkAvailability(r.organizationId(), r.locationId(), memberId,
                                r.serviceId(), start, v, null);
                        return true;
                    } catch (ApiException e) {
                        if (e.status == HttpStatus.CONFLICT || e.status == HttpStatus.BAD_REQUEST) return false;
                        throw e;
                    }
                });
        Long memberId = allocated.stream().filter(a -> a.memberId() != null)
                .map(ResourceAllocationService.Allocation::memberId).findFirst().orElse(null);
        var args = new MapSqlParameterSource().addValue("org", r.organizationId())
                .addValue("loc", r.locationId()).addValue("name", r.customerName())
                .addValue("email", r.customerEmail()).addValue("phone", r.customerPhone())
                .addValue("price", v.price()).addValue("currency", v.currency())
                .addValue("partySize", r.partySize());
        long reservationId = ((Number) db.queryForMap("""
                INSERT INTO reservations(organization_id,location_id,customer_name,customer_email,
                    customer_phone,status,total_amount,currency,party_size)
                VALUES(:org,:loc,:name,:email,:phone,'confirmed',:price,:currency,:partySize)
                RETURNING id
                """, args).get("id")).longValue();
        var itemParams = new MapSqlParameterSource()
                .addValue("org", r.organizationId()).addValue("loc", r.locationId())
                .addValue("reservation", reservationId).addValue("service", r.serviceId())
                .addValue("version", v.id()).addValue("member", memberId)
                .addValue("start", start).addValue("end", end)
                .addValue("occStart", occupiedFrom).addValue("occEnd", occupiedTo)
                .addValue("duration", (int) minutes).addValue("price", v.price())
                .addValue("currency", v.currency());
        long itemId = ((Number) db.queryForMap("""
                INSERT INTO reservation_items(organization_id,location_id,reservation_id,
                    service_id,service_version_id,member_id,starts_at,ends_at,occupied_period,
                    duration_minutes,price_amount,currency)
                VALUES(:org,:loc,:reservation,:service,:version,:member,:start,:end,
                    tstzrange(CAST(:occStart AS timestamptz),CAST(:occEnd AS timestamptz),'[)'),
                    :duration,:price,:currency)
                RETURNING id
                """, itemParams).get("id")).longValue();
        allocator.persist(r.organizationId(), r.locationId(), itemId, occupiedFrom, occupiedTo, allocated);
        String token = createToken(reservationId);
        db.update("INSERT INTO reservation_events(reservation_id,event_type) VALUES(:id,'created')",
                Map.of("id", reservationId));
        return Map.of("id", reservationId, "status", "confirmed", "startsAt", start,
                "endsAt", end, "price", v.price(), "currency", v.currency(),
                "resourceIds", allocated.stream().map(ResourceAllocationService.Allocation::resourceId).toList(),
                "manageUrl", baseUrl + "/sprava/" + token);
    }

    private void checkLocationAvailability(long org, long loc, OffsetDateTime start,
                                            OffsetDateTime end, Version v, ZoneId zone) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ensure(start.isAfter(now), "Start must be in the future");
        var localStart = start.atZoneSameInstant(zone);
        ensure(localStart.toLocalTime().toSecondOfDay() % (v.slot() * 60) == 0
                && localStart.getNano() == 0, "Start is not on a slot boundary");
        var policies = db.queryForList("SELECT min_notice_minutes,max_advance_days FROM booking_policies WHERE location_id=:loc",
                Map.of("loc", loc));
        if (!policies.isEmpty()) {
            var policy = policies.getFirst();
            ensure(!start.isBefore(now.plusMinutes(((Number) policy.get("min_notice_minutes")).longValue())), "Too little notice");
            ensure(!start.isAfter(now.plusDays(((Number) policy.get("max_advance_days")).longValue())), "Too far in advance");
        }
        if ("CUSTOM".equals(v.durationMode())) {
            // Rental: pickup and return must each happen during opening hours.
            checkOpeningTime(org, loc, start, zone);
            checkOpeningTime(org, loc, end, zone);
        } else {
            ensure(localStart.toLocalDate().equals(end.atZoneSameInstant(zone).toLocalDate()),
                    "Cross-midnight fixed services are not supported");
            checkOpeningInterval(org, loc, start, end, zone);
        }
        var blocked = db.queryForObject("""
                SELECT COUNT(*) FROM availability_overrides
                WHERE organization_id=:org AND location_id=:loc AND member_id IS NULL
                  AND override_type='unavailable'
                  AND period && tstzrange(CAST(:start AS timestamptz),CAST(:end AS timestamptz),'[)')
                """, Map.of("org", org, "loc", loc, "start", start, "end", end), Long.class);
        ensure(blocked != null && blocked == 0, "Location unavailable");
    }

    private void checkOpeningTime(long org, long loc, OffsetDateTime instant, ZoneId zone) {
        // A pickup/return at the exact closing boundary is allowed.
        checkOpeningInterval(org, loc, instant, instant, zone);
    }

    private void checkOpeningInterval(long org, long loc, OffsetDateTime start,
                                      OffsetDateTime end, ZoneId zone) {
        var from = start.atZoneSameInstant(zone);
        var to = end.atZoneSameInstant(zone);
        ensure(from.toLocalDate().equals(to.toLocalDate()), "Opening interval must fit one local day");
        var p = new MapSqlParameterSource().addValue("org", org).addValue("loc", loc)
                .addValue("day", from.toLocalDate()).addValue("weekday", from.getDayOfWeek().getValue())
                .addValue("from", from.toLocalTime()).addValue("to", to.toLocalTime());
        Boolean hasDated = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM schedule_date_overrides
                WHERE organization_id=:org AND location_id=:loc AND member_id IS NULL
                  AND kind='open' AND work_date=CAST(:day AS date))
                """, p, Boolean.class);
        String sql = Boolean.TRUE.equals(hasDated) ? """
                SELECT COUNT(*) FROM schedule_date_overrides d
                JOIN schedule_date_override_intervals i ON i.override_id=d.id
                WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id IS NULL
                  AND d.kind='open' AND d.work_date=CAST(:day AS date)
                  AND i.start_time<=CAST(:from AS time) AND i.end_time>=CAST(:to AS time)
                """ : """
                SELECT COUNT(*) FROM schedule_rules w
                WHERE w.organization_id=:org AND w.location_id=:loc AND w.kind='open'
                  AND w.weekday=:weekday AND w.effective_dates @> CAST(:day AS date)
                  AND w.start_time<=CAST(:from AS time) AND w.end_time>=CAST(:to AS time)
                """;
        Long count = db.queryForObject(sql, p, Long.class);
        ensure(count != null && count > 0, "Location is closed");
    }

    private void insertItem(long id, long org, long loc, long service, long member, OffsetDateTime start, Version v) {
        var p = new MapSqlParameterSource().addValue("id", id).addValue("org", org).addValue("loc", loc).addValue("service", service).addValue("member", member).addValue("version", v.id()).addValue("start", start).addValue("end", start.plusMinutes(v.duration())).addValue("occStart", start.minusMinutes(v.before())).addValue("occEnd", start.plusMinutes(v.duration() + v.after())).addValue("duration", v.duration()).addValue("price", v.price()).addValue("currency", v.currency());
        db.update("insert into reservation_items(organization_id,location_id,reservation_id,service_id,service_version_id,member_id,starts_at,ends_at,occupied_period,duration_minutes,price_amount,currency) values(:org,:loc,:id,:service,:version,:member,:start,:end,tstzrange(cast(:occStart as timestamptz),cast(:occEnd as timestamptz),'[)'),:duration,:price,:currency)", p);
    }

    public Object getPublic(String token) {
        var row = tokenRow(token, false);
        return Map.of("id", row.get("id"), "status", row.get("status"), "customerName", row.get("customer_name"), "totalAmount", row.get("total_amount"), "currency", row.get("currency"));
    }

    @Transactional
    public Object cancel(String token) {
        var r = tokenRow(token, true);
        ensure("manage".equals(r.get("scope")), "View-only link");
        ensure(!"cancelled".equals(r.get("status")), "Already cancelled");
        long id = ((Number) r.get("id")).longValue();
        var items = db.queryForList("select min(starts_at) as starts_at from reservation_items where reservation_id=:id and item_status='active'", Map.of("id", id));
        if (!items.isEmpty() && items.getFirst().get("starts_at") != null) {
            var start = ((java.sql.Timestamp) items.getFirst().get("starts_at")).toInstant();
            var policies = db.queryForList("select cancellation_notice_minutes from booking_policies where location_id=:loc", Map.of("loc", r.get("location_id")));
            if (!policies.isEmpty()) ensure(start.isAfter(Instant.now().plusSeconds(((Number) policies.getFirst().get("cancellation_notice_minutes")).longValue() * 60)), "Cancellation deadline passed");
        }
        db.update("update reservations set status='cancelled' where id=:id", Map.of("id", id));
        return Map.of("id", id, "status", "cancelled");
    }

    /** A permitted team member may move an existing single-worker appointment.
     *  Resource-based bookings need a separate atomic reallocation flow. */
    @Transactional
    public Object rescheduleBackoffice(long org, long reservationId, BookingDto.Reschedule request) {
        db.queryForList("SELECT id FROM organizations WHERE id=:org FOR UPDATE", Map.of("org",org));
        var reservations=db.queryForList("SELECT id,status,location_id FROM reservations "
                + "WHERE organization_id=:org AND id=:id FOR UPDATE", Map.of("org",org,"id",reservationId));
        if (reservations.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Reservation not found");
        ensure("confirmed".equals(reservations.getFirst().get("status")),"Only confirmed reservations can be moved");
        var items=db.queryForList("SELECT * FROM reservation_items "
                + "WHERE organization_id=:org AND reservation_id=:id AND item_status='active' FOR UPDATE",
                Map.of("org",org,"id",reservationId));
        ensure(items.size()==1,"Only single-worker appointments can be moved");
        var item=items.getFirst();
        long itemId=((Number)item.get("id")).longValue();
        Long resources=db.queryForObject("SELECT COUNT(*) FROM reservation_resources "
                + "WHERE organization_id=:org AND reservation_item_id=:id AND active",
                Map.of("org",org,"id",itemId),Long.class);
        if (item.get("member_id")==null || resources!=null && resources>0)
            throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","This booking requires manual reallocation");
        long member=((Number)item.get("member_id")).longValue();
        long loc=((Number)item.get("location_id")).longValue();
        long service=((Number)item.get("service_id")).longValue();
        lock(org,member);
        var start=utc(request.startsAt());
        var version=version(service,loc,start);
        ensure(version.id()==((Number)item.get("service_version_id")).longValue(),
                "Price or booking rules have changed");
        checkAvailability(org,loc,member,service,start,version,itemId);
        db.update("""
                UPDATE reservation_items SET starts_at=:start,ends_at=:end,
                    occupied_period=tstzrange(CAST(:occStart AS timestamptz),CAST(:occEnd AS timestamptz),'[)')
                WHERE organization_id=:org AND id=:id
                """,new MapSqlParameterSource().addValue("org",org).addValue("id",itemId)
                .addValue("start",start).addValue("end",start.plusMinutes(version.duration()))
                .addValue("occStart",start.minusMinutes(version.before()))
                .addValue("occEnd",start.plusMinutes(version.duration()+version.after())));
        db.update("INSERT INTO reservation_events(reservation_id,event_type,details) "
                + "VALUES(:id,'rescheduled',jsonb_build_object('startsAt',CAST(:start AS text)))",
                Map.of("id",reservationId,"start",start.toString()));
        return Map.of("id",reservationId,"startsAt",start,
                "endsAt",start.plusMinutes(version.duration()),"status","confirmed");
    }

    @Transactional
    public Object reschedule(String token, BookingDto.Reschedule request) {
        // Acquire the organization lock BEFORE the reservation lock, consistently
        // with booking creation and schedule edits (prevents opposite lock order).
        var initial = tokenRow(token, false);
        db.queryForList("SELECT id FROM organizations WHERE id=:id FOR UPDATE",
                Map.of("id", initial.get("organization_id")));
        var r = tokenRow(token, true);
        ensure("manage".equals(r.get("scope")), "View-only link");
        ensure("confirmed".equals(r.get("status")), "Reservation cannot be changed");
        long id = ((Number) r.get("id")).longValue();
        var items = db.queryForList("select * from reservation_items where reservation_id=:id and item_status='active' for update", Map.of("id", id));
        ensure(items.size() == 1, "Rescheduling multi-item reservations requires staff workflow");
        var item = items.getFirst();
        Long resourceCount = db.queryForObject(
                "SELECT COUNT(*) FROM reservation_resources WHERE reservation_item_id=:id AND active",
                Map.of("id", item.get("id")), Long.class);
        if (item.get("member_id") == null || (resourceCount != null && resourceCount > 0))
            throw new ApiException(HttpStatus.CONFLICT,
                    "Rescheduling resource-based reservations requires backoffice handling");
        long org = ((Number) r.get("organization_id")).longValue(), loc = ((Number) r.get("location_id")).longValue(), member = ((Number) item.get("member_id")).longValue(), service = ((Number) item.get("service_id")).longValue(), itemId = ((Number) item.get("id")).longValue();
        lock(org, member);
        var start = utc(request.startsAt());
        var v = version(service, loc, start);
        ensure(v.id() == ((Number) item.get("service_version_id")).longValue(), "Price/version changed: manual confirmation required");
        checkAvailability(org, loc, member, service, start, v, itemId);
        db.update("update reservation_items set starts_at=:start,ends_at=:end,occupied_period=tstzrange(cast(:occStart as timestamptz),cast(:occEnd as timestamptz),'[)') where id=:id", new MapSqlParameterSource().addValue("id", itemId).addValue("start", start).addValue("end", start.plusMinutes(v.duration())).addValue("occStart", start.minusMinutes(v.before())).addValue("occEnd", start.plusMinutes(v.duration() + v.after())));
        db.update("insert into reservation_events(reservation_id,event_type,details) values(:id,'rescheduled',jsonb_build_object('startsAt',cast(:at as text)))", Map.of("id", id, "at", start.toString()));
        return Map.of("id", id, "startsAt", start, "endsAt", start.plusMinutes(v.duration()), "status", "confirmed");
    }
}
