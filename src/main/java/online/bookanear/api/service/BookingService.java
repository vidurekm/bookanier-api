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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

@Service
public class BookingService {
    private final NamedParameterJdbcTemplate db;
    private final String baseUrl;
    private final ResourceAllocationService allocator;
    private final ReservationNotificationService notifications;
    private final SecureRandom random = new SecureRandom();

    BookingService(NamedParameterJdbcTemplate db, @Value("${app.public-base-url}") String baseUrl,
                   ResourceAllocationService allocator, ReservationNotificationService notifications) {
        this.db = db;
        this.baseUrl = baseUrl;
        this.allocator = allocator;
        this.notifications = notifications;
    }

    private boolean resourceDemandsMatch(long org, long serviceId,
                                         List<ResourceAllocationService.Demand> demands) {
        List<Long> configured = db.queryForList("""
                SELECT req.resource_type_id FROM service_resource_requirements req
                JOIN resource_types rt ON rt.organization_id=:org AND rt.id=req.resource_type_id
                  AND rt.active
                WHERE req.service_id=:service
                ORDER BY req.resource_type_id
                """, Map.of("org", org, "service", serviceId)).stream()
                .map(row -> ((Number) row.get("resource_type_id")).longValue()).toList();
        return configured.equals(demands.stream().map(ResourceAllocationService.Demand::typeId)
                .sorted().toList());
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

    private int maxAdvanceDays(long org, long loc) {
        return db.queryForObject("""
                SELECT LEAST(COALESCE(p.max_advance_days,3650),
                             COALESCE((c.settings->>'maxAdvanceDays')::integer,180))
                FROM organization_booking_config c
                LEFT JOIN booking_policies p ON p.organization_id=c.organization_id AND p.location_id=:loc
                WHERE c.organization_id=:org
                """, Map.of("org",org,"loc",loc), Integer.class);
    }

    private static OffsetDateTime utc(OffsetDateTime dt) {
        return dt.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("Unsupported reservation timestamp type");
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
        var rows = db.queryForList("select l.timezone from organizations o join locations l on l.organization_id=o.id and l.id=:loc join organization_members m on m.organization_id=o.id and m.id=:member join member_locations ml on ml.member_id=m.id and ml.location_id=l.id join services s on s.organization_id=o.id and s.id=:svc join worker_services ws on ws.member_id=m.id and ws.service_id=s.id and ws.active join location_services ls on ls.location_id=l.id and ls.service_id=s.id and ls.active where o.id=:org and o.booking_enabled and l.active and l.archived_at is null and m.active and m.provides_services and m.archived_at is null and s.active", Map.of("org", org, "loc", loc, "member", member, "svc", service));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Service/member/location not configured");
        return rows.getFirst();
    }

    private void lock(long org, long member) {
        db.queryForList("select id from organizations where id=:id for update", Map.of("id", org));
        db.queryForList("select id from organization_members where id=:id and organization_id=:org for update", Map.of("id", member, "org", org));
    }

    private void checkAvailability(long org, long loc, long member, long service, OffsetDateTime start, Version v, Long excludeItem) {
        checkAvailability(org, loc, member, service, start, v, excludeItem, true);
    }

    /** Staff moving an existing booking may pick an ongoing or past time, so timing rules are skipped. */
    private void checkAvailability(long org, long loc, long member, long service, OffsetDateTime start, Version v, Long excludeItem, boolean enforceTiming) {
        var zone = ZoneId.of(context(org, loc, member, service).get("timezone").toString());
        var local = start.atZoneSameInstant(zone);
        var end = start.plusMinutes(v.duration());
        if (enforceTiming) ensure(start.isAfter(OffsetDateTime.now(ZoneOffset.UTC)), "Start must be in the future");
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
        var overlaps = db.queryForObject("""
                SELECT
                  (SELECT count(*) FROM reservation_items
                   WHERE member_id=:member AND item_status='active' AND id<>:exclude
                     AND occupied_period && tstzrange(CAST(:occStart AS timestamptz),CAST(:occEnd AS timestamptz),'[)'))
                  +
                  (SELECT count(*) FROM reservation_resources rr
                   JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id
                   WHERE res.member_id=:member AND rr.organization_id=:org AND rr.active
                     AND rr.reservation_item_id<>:exclude
                     AND rr.occupied_period && tstzrange(CAST(:occStart AS timestamptz),CAST(:occEnd AS timestamptz),'[)'))
                """, p, Long.class);
        ensure(overlaps != null && overlaps == 0, "Slot occupied");
        if (!enforceTiming) return;
        var policy = db.queryForList("select min_notice_minutes,max_advance_days from booking_policies where location_id=:loc", Map.of("loc", loc));
        if (!policy.isEmpty()) {
            var policyRow = policy.getFirst();
            ensure(!start.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(((Number) policyRow.get("min_notice_minutes")).longValue())), "Too little notice");
        }
        ensure(!start.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(maxAdvanceDays(org,loc))), "Too far in advance");
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
        return create(r, true);
    }

    @Transactional
    public Map<String, Object> create(BookingDto.CreateBooking r, boolean enforceCustomerChoices) {
        var start = utc(r.startsAt());
        db.queryForList("SELECT id FROM organizations WHERE id=:id FOR UPDATE",
                Map.of("id", r.organizationId()));
        Long memberId=r.memberId();
        if (enforceCustomerChoices) {
            Boolean customerCanChoose=db.queryForObject("""
                    SELECT customer_can_choose_staff FROM services
                    WHERE organization_id=:org AND id=:service
                    """,Map.of("org",r.organizationId(),"service",r.serviceId()),Boolean.class);
            if (!Boolean.TRUE.equals(customerCanChoose)) {
                var candidates=db.queryForList("""
                        SELECT m.id FROM organization_members m
                        JOIN member_locations ml ON ml.organization_id=m.organization_id AND ml.member_id=m.id
                        JOIN worker_services ws ON ws.organization_id=m.organization_id AND ws.member_id=m.id
                        WHERE m.organization_id=:org AND ml.location_id=:loc AND ws.service_id=:service
                          AND ws.active AND m.active AND m.provides_services AND m.archived_at IS NULL
                        ORDER BY (SELECT COALESCE(SUM(ri.duration_minutes),0) FROM reservation_items ri
                                  JOIN reservations rs ON rs.id=ri.reservation_id
                                  JOIN locations lo ON lo.id=:loc
                                  WHERE ri.organization_id=:org AND ri.member_id=m.id AND rs.status<>'cancelled'
                                    AND (ri.starts_at AT TIME ZONE lo.timezone)::date
                                      = (CAST(:start AS timestamptz) AT TIME ZONE lo.timezone)::date), m.id
                        """,Map.of("org",r.organizationId(),"loc",r.locationId(),"service",r.serviceId(),"start",start));
                boolean available=false;
                for (var candidate:candidates) {
                    long candidateId=((Number)candidate.get("id")).longValue();
                    lock(r.organizationId(),candidateId);
                    try {
                        checkAvailability(r.organizationId(),r.locationId(),candidateId,
                                r.serviceId(),start,version(r.serviceId(),r.locationId(),start),null);
                        memberId=candidateId;
                        available=true;
                        break;
                    } catch (ApiException ex) {
                        if (ex.status!=HttpStatus.CONFLICT && ex.status!=HttpStatus.BAD_REQUEST) throw ex;
                    }
                }
                if (!available) throw new ApiException(HttpStatus.CONFLICT,"INSUFFICIENT_RESOURCES",
                        "No staff member is available for this time");
            } else if (memberId==null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Choose a staff member");
            }
        }
        if (memberId==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Choose a staff member");
        lock(r.organizationId(), memberId);
        Version v = version(r.serviceId(), r.locationId(), start);
        checkAvailability(r.organizationId(), r.locationId(), memberId, r.serviceId(), start, v, null);
        var args = new MapSqlParameterSource().addValue("org", r.organizationId()).addValue("loc", r.locationId()).addValue("name", r.customerName()).addValue("email", r.customerEmail()).addValue("phone", r.customerPhone()).addValue("price", v.price()).addValue("currency", v.currency());
        long id = ((Number) db.queryForMap("insert into reservations(organization_id,location_id,customer_name,customer_email,customer_phone,status,total_amount,currency) values(:org,:loc,:name,:email,:phone,'confirmed',:price,:currency) returning id", args).get("id")).longValue();
        insertItem(id, r.organizationId(), r.locationId(), r.serviceId(), memberId, start, v);
        String token = createToken(id);
        db.update("insert into reservation_events(reservation_id,event_type) values(:id,'created')", Map.of("id", id));
        return Map.of("id", id, "status", "confirmed", "startsAt", start, "endsAt", start.plusMinutes(v.duration()), "price", v.price(), "currency", v.currency(), "manageUrl", baseUrl + "/sprava/" + token);
    }

    /** Public universal booking; serializes allocations by locking the tenant row. */
    @Transactional
    public Map<String, Object> createUniversal(BookingDto.CreateUniversalBooking r) {
        return createUniversal(r, true);
    }

    @Transactional
    public Map<String, Object> createUniversal(BookingDto.CreateUniversalBooking r, boolean enforceCustomerChoices) {
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
                JOIN locations l ON l.organization_id=o.id AND l.id=:loc AND l.active AND l.archived_at IS NULL
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
                }, enforceCustomerChoices);
        Long memberId = allocated.stream().filter(a -> a.memberId() != null)
                .map(ResourceAllocationService.Allocation::memberId).findFirst().orElse(null);
        if (memberId == null && !enforceCustomerChoices) {
            // Back office may assign a worker to a service that has no staff requirement.
            Long chosen = r.preferredMemberId() != null ? r.preferredMemberId()
                    : r.preferredMemberIds() != null && r.preferredMemberIds().size() == 1
                    ? r.preferredMemberIds().getFirst() : null;
            if (chosen != null) {
                checkAvailability(r.organizationId(), r.locationId(), chosen, r.serviceId(), start, v, null);
                memberId = chosen;
            }
        }
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
        checkLocationAvailability(org, loc, start, end, v, zone, true);
    }

    private void checkLocationAvailability(long org, long loc, OffsetDateTime start,
                                            OffsetDateTime end, Version v, ZoneId zone,
                                            boolean enforceTiming) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var localStart = start.atZoneSameInstant(zone);
        ensure(localStart.toLocalTime().toSecondOfDay() % (v.slot() * 60) == 0
                && localStart.getNano() == 0, "Start is not on a slot boundary");
        if (enforceTiming) {
            ensure(start.isAfter(now), "Start must be in the future");
            var policies = db.queryForList("SELECT min_notice_minutes,max_advance_days FROM booking_policies WHERE location_id=:loc",
                    Map.of("loc", loc));
            if (!policies.isEmpty()) {
                var policy = policies.getFirst();
                ensure(!start.isBefore(now.plusMinutes(((Number) policy.get("min_notice_minutes")).longValue())), "Too little notice");
            }
            ensure(!start.isAfter(now.plusDays(maxAdvanceDays(org,loc))), "Too far in advance");
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
        notifications.sendCancelled(((Number) r.get("organization_id")).longValue(), id);
        return Map.of("id", id, "status", "cancelled");
    }

    public Object rescheduleAvailability(long org, long reservationId, LocalDate date,
                                         List<Long> overrideMembers, Long overrideResource) {
        var item = rescheduleItem(org, reservationId, false);
        ZoneId zone = ZoneId.of(item.get("timezone").toString());
        if (date.isBefore(LocalDate.now(zone).minusYears(1)) || date.isAfter(LocalDate.now(zone).plusYears(10)))
            return Map.of("date", date.toString(), "slots", List.of(), "reason", "out_of_range");

        long itemId = ((Number) item.get("id")).longValue();
        long locationId = ((Number) item.get("location_id")).longValue();
        long serviceId = ((Number) item.get("service_id")).longValue();
        int step = ((Number) item.get("slot_interval_minutes")).intValue();
        int duration = ((Number) item.get("duration_minutes")).intValue();
        int before = ((Number) item.get("buffer_before_minutes")).intValue();
        int after = ((Number) item.get("buffer_after_minutes")).intValue();
        boolean hasResources = hasResourceRequirements(org, serviceId, itemId);
        var choice = rescheduleChoice(org, item, itemId, serviceId, hasResources, overrideMembers, overrideResource);
        boolean chooseStaff = choice.chooseStaff();
        List<Long> preferredMembers = choice.members();
        List<ResourceAllocationService.Demand> demands = choice.demands();
        Long extraMember = choice.extraMember();
        if (hasResources && !resourceDemandsMatch(org, serviceId, demands))
            return Map.of("date", date.toString(), "slots", List.of(), "reason", "resources_changed");
        if (hasResources && chooseStaff && preferredMembers.size() != staffRequirementCount(org, serviceId))
            return Map.of("date", date.toString(), "slots", List.of(), "reason", "resources_changed");

        var slots = new ArrayList<Map<String, Object>>();
        var intervals = openingIntervals(org, locationId, date);
        for (var interval : intervals) {
            LocalTime from = asLocalTime(interval.get("start_time"));
            LocalTime to = asLocalTime(interval.get("end_time"));
            int first = ((from.toSecondOfDay() + step * 60 - 1) / (step * 60)) * step;
            for (int minute = first; minute < to.toSecondOfDay() / 60 && slots.size() < 150; minute += step) {
                LocalTime time = LocalTime.of(minute / 60, minute % 60);
                LocalDateTime local = LocalDateTime.of(date, time);
                ZonedDateTime zoned = local.atZone(zone);
                if (!zoned.toLocalDateTime().equals(local)) continue;
                OffsetDateTime start = zoned.toOffsetDateTime();
                Version version;
                try {
                    version = version(serviceId, locationId, start);
                } catch (ApiException exception) {
                    if (exception.status == HttpStatus.NOT_FOUND) continue;
                    throw exception;
                }
                if (version.id() != ((Number) item.get("service_version_id")).longValue()) continue;
                OffsetDateTime end = start.plusMinutes(duration);
                try {
                    checkLocationAvailability(org, locationId, start, end, version, zone, false);
                    Map<String, Object> slot = availableRescheduleSlot(org, item, start, end, version,
                            itemId, hasResources, chooseStaff, preferredMembers, demands, before, after, extraMember);
                    if (slot != null) slots.add(slot);
                } catch (ApiException exception) {
                    if (exception.status != HttpStatus.CONFLICT && exception.status != HttpStatus.BAD_REQUEST)
                        throw exception;
                }
            }
            if (slots.size() >= 150) break;
        }
        String reason = !slots.isEmpty() ? "" : intervals.isEmpty() ? "closed" : "booked";
        return Map.of("date", date.toString(), "slots", slots, "reason", reason);
    }

    @Transactional
    public Object rescheduleBackoffice(long org, long reservationId, BookingDto.Reschedule request) {
        db.queryForList("SELECT id FROM organizations WHERE id=:org FOR UPDATE", Map.of("org", org));
        db.queryForList("SELECT id FROM reservations WHERE organization_id=:org AND id=:id FOR UPDATE",
                Map.of("org", org, "id", reservationId));
        var item = rescheduleItem(org, reservationId, true);
        long itemId = ((Number) item.get("id")).longValue();
        long locationId = ((Number) item.get("location_id")).longValue();
        long serviceId = ((Number) item.get("service_id")).longValue();
        var start = utc(request.startsAt());
        var version = version(serviceId, locationId, start);
        ensure(version.id() == ((Number) item.get("service_version_id")).longValue(),
                "Price or booking rules have changed");
        ZoneId zone = ZoneId.of(item.get("timezone").toString());
        int duration = ((Number) item.get("duration_minutes")).intValue();
        ensure("FIXED".equals(version.durationMode()) || version.maxDuration() == null
                        || duration <= version.maxDuration(),
                "Reservation duration exceeds the service limit");
        var end = start.plusMinutes(duration);
        checkLocationAvailability(org, locationId, start, end, version, zone, false);

        boolean hasResources = hasResourceRequirements(org, serviceId, itemId);
        var choice = rescheduleChoice(org, item, itemId, serviceId, hasResources,
                request.preferredMemberIds(), request.preferredResourceId());
        boolean chooseStaff = choice.chooseStaff();
        List<Long> preferredMembers = choice.members();
        List<ResourceAllocationService.Demand> demands = choice.demands();
        Long extraMember = choice.extraMember();
        Long memberId = null;
        List<ResourceAllocationService.Allocation> allocations = List.of();
        int before = version.before(), after = version.after();

        if (hasResources) {
            ensure(resourceDemandsMatch(org, serviceId, demands),
                    "Service resource requirements have changed; reallocation is required");
            ensure(!chooseStaff || preferredMembers.size() == staffRequirementCount(org, serviceId),
                    "The originally selected staff members are no longer configured");
            var allocationRequest = new BookingDto.CreateUniversalBooking(org, locationId, serviceId,
                    start, end, rescheduleParty(org, serviceId, item), null, null,
                    preferredMembers.isEmpty() ? null : preferredMembers.getFirst(),
                    preferredMembers.isEmpty() ? null : preferredMembers,
                    "reschedule", null, null, null);
            allocations = allocator.allocate(org, locationId, serviceId,
                    start.minusMinutes(before), end.plusMinutes(after), allocationRequest,
                    candidate -> staffAvailable(org, locationId, serviceId, start, version, itemId,
                            candidate, "FIXED".equals(version.durationMode())),
                    false, itemId, demands);
            memberId = allocations.stream().filter(a -> a.memberId() != null)
                    .map(ResourceAllocationService.Allocation::memberId).findFirst().orElse(null);
            if (memberId == null && extraMember != null) {
                ensure(staffAvailable(org, locationId, serviceId, start, version, itemId, extraMember,
                        "FIXED".equals(version.durationMode())), "The selected staff member is not available");
                memberId = extraMember;
            }
            for (long selectedMember : allocations.stream().map(ResourceAllocationService.Allocation::memberId)
                    .filter(Objects::nonNull).distinct().sorted().toList())
                lock(org, selectedMember);
            if (memberId != null) lock(org, memberId);
        } else {
            List<Long> candidates = chooseStaff ? preferredMembers
                    : eligibleMembers(org, locationId, serviceId, start);
            for (long candidate : candidates) {
                if (staffAvailable(org, locationId, serviceId, start, version, itemId,
                        candidate, true)) {
                    memberId = candidate;
                    break;
                }
            }
            ensure(memberId != null, "No staff member is available for this time");
            lock(org, memberId);
        }

        db.update("""
                UPDATE reservation_items SET member_id=:member,starts_at=:start,ends_at=:end,
                    occupied_period=tstzrange(CAST(:occStart AS timestamptz),CAST(:occEnd AS timestamptz),'[)')
                WHERE organization_id=:org AND id=:id
                """, new MapSqlParameterSource().addValue("org", org).addValue("id", itemId)
                .addValue("member", memberId).addValue("start", start).addValue("end", end)
                .addValue("occStart", start.minusMinutes(before))
                .addValue("occEnd", end.plusMinutes(after)));
        if (hasResources) allocator.replaceAssignments(org, locationId, itemId,
                start.minusMinutes(before), end.plusMinutes(after), allocations);
        db.update("INSERT INTO reservation_events(reservation_id,event_type,details) "
                        + "VALUES(:id,'rescheduled',jsonb_build_object('startsAt',CAST(:start AS text)))",
                Map.of("id", reservationId, "start", start.toString()));
        if (!instant(item.get("starts_at")).equals(start.toInstant()))
            notifications.sendRescheduled(org, reservationId, start);
        return Map.of("id", reservationId, "startsAt", start, "endsAt", end, "status", "confirmed");
    }

    private Integer rescheduleParty(long org, long serviceId, Map<String, Object> item) {
        Long spaces = db.queryForObject("SELECT COUNT(*) FROM service_resource_requirements req JOIN resource_types rt ON rt.organization_id=:org AND rt.id=req.resource_type_id AND rt.active AND rt.category='space' WHERE req.service_id=:service", Map.of("org", org, "service", serviceId), Long.class);
        return spaces != null && spaces > 0 ? nullableInt(item.get("party_size")) : null;
    }

    private record RescheduleChoice(boolean chooseStaff, List<Long> members,
                                    List<ResourceAllocationService.Demand> demands, Long extraMember) {}

    private RescheduleChoice rescheduleChoice(long org, Map<String, Object> item, long itemId, long serviceId,
                                              boolean hasResources, List<Long> overrideMembers, Long overrideResource) {
        boolean override = overrideMembers != null && !overrideMembers.isEmpty();
        boolean originalChoose = Boolean.TRUE.equals(item.get("customer_can_choose_staff"));
        boolean chooseResource = Boolean.TRUE.equals(item.get("customer_can_choose_resource"));
        boolean staffService = staffRequirementCount(org, serviceId) > 0;
        boolean chooseStaff;
        List<Long> members;
        Long extraMember = null;
        if (hasResources && !staffService) {
            chooseStaff = false;
            members = List.of();
            extraMember = override ? overrideMembers.getFirst()
                    : item.get("member_id") == null ? null : ((Number) item.get("member_id")).longValue();
        } else {
            chooseStaff = override || originalChoose;
            members = override ? overrideMembers : preferredMembers(org, itemId, item, hasResources, originalChoose);
        }
        var demands = resourceDemands(org, itemId, chooseResource || overrideResource != null);
        if (overrideResource != null) {
            var rows = db.queryForList("SELECT resource_type_id FROM resources WHERE organization_id=:org AND id=:id",
                    Map.of("org", org, "id", overrideResource));
            ensure(!rows.isEmpty(), "Resource not found");
            long typeId = ((Number) rows.getFirst().get("resource_type_id")).longValue();
            demands = demands.stream().map(d -> d.typeId() == typeId
                    ? new ResourceAllocationService.Demand(d.typeId(), d.quantity(), d.minCapacity(), overrideResource)
                    : d).toList();
        }
        return new RescheduleChoice(chooseStaff, members, demands, extraMember);
    }

    public Object rescheduleOptions(long org, long reservationId) {
        var item = rescheduleItem(org, reservationId, false);
        long itemId = ((Number) item.get("id")).longValue();
        long locationId = ((Number) item.get("location_id")).longValue();
        long serviceId = ((Number) item.get("service_id")).longValue();
        long staffCount = staffRequirementCount(org, serviceId);
        var members = db.queryForList("""
                SELECT m.id,m.display_name FROM organization_members m
                JOIN member_locations ml ON ml.organization_id=m.organization_id AND ml.member_id=m.id
                JOIN worker_services ws ON ws.organization_id=m.organization_id AND ws.member_id=m.id AND ws.active
                WHERE m.organization_id=:org AND ml.location_id=:loc AND ws.service_id=:service
                  AND m.active AND m.provides_services AND m.archived_at IS NULL
                GROUP BY m.id,m.display_name ORDER BY m.display_name
                """, Map.of("org", org, "loc", locationId, "service", serviceId));
        var nonStaff = db.queryForList("""
                SELECT req.resource_type_id,req.quantity FROM service_resource_requirements req
                JOIN resource_types rt ON rt.organization_id=:org AND rt.id=req.resource_type_id
                  AND rt.active AND rt.category<>'staff'
                WHERE req.service_id=:service
                """, Map.of("org", org, "service", serviceId));
        List<Map<String, Object>> resources = List.of();
        Object currentResource = null;
        if (nonStaff.size() == 1 && ((Number) nonStaff.getFirst().get("quantity")).intValue() == 1) {
            long typeId = ((Number) nonStaff.getFirst().get("resource_type_id")).longValue();
            resources = db.queryForList("""
                    SELECT r.id,r.name FROM resources r
                    WHERE r.organization_id=:org AND r.location_id=:loc AND r.resource_type_id=:type
                      AND r.active AND r.resource_state='available' ORDER BY r.name
                    """, Map.of("org", org, "loc", locationId, "type", typeId));
            var current = db.queryForList("""
                    SELECT rr.resource_id FROM reservation_resources rr
                    JOIN resources r ON r.organization_id=rr.organization_id AND r.id=rr.resource_id
                    WHERE rr.organization_id=:org AND rr.reservation_item_id=:item AND rr.active
                      AND r.resource_type_id=:type
                    """, Map.of("org", org, "item", itemId, "type", typeId));
            if (!current.isEmpty()) currentResource = current.getFirst().get("resource_id");
        }
        var currentMembers = hasResourceRequirements(org, serviceId, itemId) && staffCount > 0
                ? preferredMembers(org, itemId, item, true, true)
                : item.get("member_id") == null ? List.<Long>of() : List.of(((Number) item.get("member_id")).longValue());
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("staffCount", staffCount);
        result.put("members", members);
        result.put("currentMemberIds", currentMembers);
        result.put("resources", resources);
        result.put("currentResourceId", currentResource);
        return result;
    }

    private Map<String, Object> rescheduleItem(long org, long reservationId, boolean forUpdate) {
        var reservations = db.queryForList("SELECT status FROM reservations "
                + "WHERE organization_id=:org AND id=:id", Map.of("org", org, "id", reservationId));
        if (reservations.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Reservation not found");
        ensure("confirmed".equals(reservations.getFirst().get("status")),
                "Only confirmed reservations can be moved");
        var items = db.queryForList("""
                SELECT r.party_size,ri.*,l.timezone,sv.slot_interval_minutes,
                       sv.buffer_before_minutes,sv.buffer_after_minutes,sv.duration_mode,
                       sv.max_duration_minutes,s.customer_can_choose_staff,
                       s.customer_can_choose_resource
                FROM reservations r
                JOIN reservation_items ri ON ri.organization_id=r.organization_id
                  AND ri.reservation_id=r.id AND ri.item_status='active'
                JOIN locations l ON l.organization_id=ri.organization_id AND l.id=ri.location_id
                JOIN services s ON s.organization_id=ri.organization_id AND s.id=ri.service_id
                JOIN service_versions sv ON sv.service_id=ri.service_id AND sv.id=ri.service_version_id
                WHERE r.organization_id=:org AND r.id=:id
                """ + (forUpdate ? " FOR UPDATE OF ri" : ""),
                Map.of("org", org, "id", reservationId));
        ensure(items.size() == 1, "Only single-item reservations can be moved");
        return items.getFirst();
    }

    private boolean hasResourceRequirements(long org, long serviceId, long itemId) {
        Long count = db.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM service_resource_requirements req
                   JOIN resource_types rt ON rt.organization_id=:org AND rt.id=req.resource_type_id
                     AND rt.active WHERE req.service_id=:service)
                  + (SELECT COUNT(*) FROM reservation_item_resource_demands
                     WHERE organization_id=:org AND reservation_item_id=:item)
                  + (SELECT COUNT(*) FROM reservation_resources
                     WHERE organization_id=:org AND reservation_item_id=:item AND active)
                """, Map.of("org", org, "service", serviceId, "item", itemId), Long.class);
        return count != null && count > 0;
    }

    private long staffRequirementCount(long org, long serviceId) {
        Long count = db.queryForObject("""
                SELECT COALESCE(SUM(req.quantity),0) FROM service_resource_requirements req
                JOIN resource_types rt ON rt.organization_id=:org AND rt.id=req.resource_type_id
                  AND rt.category='staff' AND rt.active
                WHERE req.service_id=:service
                """, Map.of("org", org, "service", serviceId), Long.class);
        return count == null ? 0 : count;
    }

    private List<Long> preferredMembers(long org, long itemId, Map<String, Object> item,
                                        boolean hasResources, boolean preserveChoice) {
        if (!preserveChoice) return List.of();
        var rows = hasResources ? db.queryForList("""
                SELECT DISTINCT res.member_id
                FROM reservation_resources rr
                JOIN resources res ON res.organization_id=rr.organization_id
                  AND res.location_id=rr.location_id AND res.id=rr.resource_id
                JOIN resource_types rt ON rt.organization_id=res.organization_id
                  AND rt.id=res.resource_type_id AND rt.category='staff'
                WHERE rr.organization_id=:org AND rr.reservation_item_id=:item
                  AND rr.active AND res.member_id IS NOT NULL
                ORDER BY res.member_id
                """, Map.of("org", org, "item", itemId)) : List.<Map<String, Object>>of();
        var members = rows.stream().map(row -> ((Number) row.get("member_id")).longValue()).toList();
        if (!hasResources && item.get("member_id") != null)
            return List.of(((Number) item.get("member_id")).longValue());
        return members;
    }

    private List<ResourceAllocationService.Demand> resourceDemands(long org, long itemId,
                                                                    boolean preserveResourceChoice) {
        return db.query("""
                SELECT resource_type_id,quantity,min_capacity,preferred_resource_id
                FROM reservation_item_resource_demands
                WHERE organization_id=:org AND reservation_item_id=:item
                ORDER BY resource_type_id
                """, Map.of("org", org, "item", itemId), (rs, row) ->
                new ResourceAllocationService.Demand(rs.getLong("resource_type_id"),
                        rs.getInt("quantity"), (Integer) rs.getObject("min_capacity"),
                        preserveResourceChoice && rs.getObject("preferred_resource_id") != null
                                ? rs.getLong("preferred_resource_id") : null));
    }

    private List<Long> eligibleMembers(long org, long locationId, long serviceId, OffsetDateTime start) {
        return db.queryForList("""
                SELECT m.id FROM organization_members m
                JOIN member_locations ml ON ml.organization_id=m.organization_id AND ml.member_id=m.id
                JOIN worker_services ws ON ws.organization_id=m.organization_id AND ws.member_id=m.id
                  AND ws.active
                WHERE m.organization_id=:org AND ml.location_id=:loc AND ws.service_id=:service
                  AND m.active AND m.provides_services AND m.archived_at IS NULL
                GROUP BY m.id
                ORDER BY (SELECT COALESCE(SUM(ri.duration_minutes),0) FROM reservation_items ri
                          JOIN reservations rs ON rs.id=ri.reservation_id
                          JOIN locations lo ON lo.id=:loc
                          WHERE ri.organization_id=:org AND ri.member_id=m.id AND rs.status<>'cancelled'
                            AND (ri.starts_at AT TIME ZONE lo.timezone)::date
                              = (CAST(:start AS timestamptz) AT TIME ZONE lo.timezone)::date), m.id
                """, Map.of("org", org, "loc", locationId, "service", serviceId, "start", start))
                .stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private boolean staffAvailable(long org, long locationId, long serviceId, OffsetDateTime start,
                                   Version version, long itemId, long memberId, boolean fixedDuration) {
        if (!fixedDuration) return false;
        try {
            checkAvailability(org, locationId, memberId, serviceId, start, version, itemId, false);
            return true;
        } catch (ApiException exception) {
            if (exception.status == HttpStatus.CONFLICT || exception.status == HttpStatus.BAD_REQUEST)
                return false;
            throw exception;
        }
    }

    private Map<String, Object> availableRescheduleSlot(long org, Map<String, Object> item,
            OffsetDateTime start, OffsetDateTime end, Version version, long itemId,
            boolean hasResources, boolean chooseStaff, List<Long> preferredMembers,
            List<ResourceAllocationService.Demand> demands, int before, int after, Long extraMember) {
        long locationId = ((Number) item.get("location_id")).longValue();
        long serviceId = ((Number) item.get("service_id")).longValue();
        if (hasResources) {
            if (chooseStaff && preferredMembers.size() != staffRequirementCount(org, serviceId))
                return null;
            var allocationRequest = new BookingDto.CreateUniversalBooking(org, locationId, serviceId,
                    start, end, rescheduleParty(org, serviceId, item), null, null,
                    preferredMembers.isEmpty() ? null : preferredMembers.getFirst(),
                    preferredMembers.isEmpty() ? null : preferredMembers,
                    "availability", null, null, null);
            try {
                allocator.allocate(org, locationId, serviceId, start.minusMinutes(before),
                        end.plusMinutes(after), allocationRequest,
                        candidate -> staffAvailable(org, locationId, serviceId, start, version,
                                itemId, candidate, "FIXED".equals(version.durationMode())),
                        false, itemId, demands);
            } catch (ApiException exception) {
                if (exception.status == HttpStatus.CONFLICT || exception.status == HttpStatus.BAD_REQUEST)
                    return null;
                throw exception;
            }
            if (extraMember != null && !staffAvailable(org, locationId, serviceId, start, version,
                    itemId, extraMember, "FIXED".equals(version.durationMode()))) return null;
        } else {
            List<Long> candidates = chooseStaff ? preferredMembers
                    : eligibleMembers(org, locationId, serviceId, start);
            if (candidates.stream().noneMatch(candidate -> staffAvailable(org, locationId, serviceId,
                    start, version, itemId, candidate, true))) return null;
        }
        return Map.of("time", start.atZoneSameInstant(ZoneId.of(item.get("timezone").toString()))
                        .toLocalTime().toString(),
                "startsAt", start);
    }

    private List<Map<String, Object>> openingIntervals(long org, long locationId, LocalDate date) {
        var params = new MapSqlParameterSource().addValue("org", org).addValue("loc", locationId)
                .addValue("day", date).addValue("weekday", date.getDayOfWeek().getValue());
        Boolean dated = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM schedule_date_overrides
                WHERE organization_id=:org AND location_id=:loc AND member_id IS NULL
                  AND kind='open' AND work_date=CAST(:day AS date))
                """, params, Boolean.class);
        if (Boolean.TRUE.equals(dated)) return db.queryForList("""
                SELECT i.start_time,i.end_time FROM schedule_date_overrides d
                JOIN schedule_date_override_intervals i ON i.override_id=d.id
                WHERE d.organization_id=:org AND d.location_id=:loc AND d.member_id IS NULL
                  AND d.kind='open' AND d.work_date=CAST(:day AS date)
                ORDER BY i.start_time
                """, params);
        return db.queryForList("""
                SELECT start_time,end_time FROM schedule_rules
                WHERE organization_id=:org AND location_id=:loc AND member_id IS NULL
                  AND kind='open' AND weekday=:weekday
                  AND effective_dates @> CAST(:day AS date)
                ORDER BY start_time
                """, params);
    }

    private static LocalTime asLocalTime(Object value) {
        if (value instanceof LocalTime time) return time;
        if (value instanceof java.sql.Time time) return time.toLocalTime();
        return LocalTime.parse(value.toString());
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
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
        if (!instant(item.get("starts_at")).equals(start.toInstant()))
            notifications.sendRescheduled(((Number) r.get("organization_id")).longValue(), id, start);
        return Map.of("id", id, "startsAt", start, "endsAt", start.plusMinutes(v.duration()), "status", "confirmed");
    }
}
