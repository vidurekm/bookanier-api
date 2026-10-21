package online.bookanear.api.service;

import online.bookanear.api.dto.EventDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class EventService {
    private final NamedParameterJdbcTemplate db;
    private final SecureRandom random=new SecureRandom();
    @Value("${registration.home-url:http://localhost:5173/}") private String publicUrl;
    public EventService(NamedParameterJdbcTemplate db) { this.db=db; }
    private Map<String,Object> one(String sql,Map<String,?> args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","Event not found");
        return rows.getFirst();
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private String token() { byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void eventMode(long org) {
        var c=one("SELECT booking_model,setup_status FROM organization_booking_config WHERE organization_id=:org",Map.of("org",org));
        if(!"EVENT".equals(c.get("booking_model")) || !"complete".equals(c.get("setup_status")))
            throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","Group events not enabled for this organization");
    }
    public Object series(long org) {
        eventMode(org);
        return db.queryForList("SELECT * FROM event_series WHERE organization_id=:org ORDER BY id DESC",Map.of("org",org));
    }
    @Transactional
    public Object createSeries(long org,EventDto.Series r) {
        eventMode(org);
        one("SELECT id FROM locations WHERE organization_id=:org AND id=:loc AND active",Map.of("org",org,"loc",r.locationId()));
        return one("INSERT INTO event_series(organization_id,location_id,title,description,capacity,price,currency) VALUES(:org,:loc,:title,:description,:capacity,:price,:currency) RETURNING *",
            new MapSqlParameterSource().addValue("org",org).addValue("loc",r.locationId()).addValue("title",r.title().trim()).addValue("description",r.description()).addValue("capacity",r.capacity()).addValue("price",r.price()).addValue("currency",r.currency()==null?"CZK":r.currency()).getValues());
    }
    @Transactional
    public Object updateSeries(long org,long id,EventDto.Series r) {
        eventMode(org);
        one("SELECT id FROM event_series WHERE organization_id=:org AND id=:id FOR UPDATE",Map.of("org",org,"id",id));
        one("SELECT id FROM locations WHERE organization_id=:org AND id=:loc AND active",Map.of("org",org,"loc",r.locationId()));
        // Serialize capacity edits with seat purchases on each affected occurrence.
        db.queryForList("SELECT id FROM event_occurrences WHERE organization_id=:org AND series_id=:id ORDER BY id FOR UPDATE",Map.of("org",org,"id",id));
        Long over=db.queryForObject("""
            SELECT count(*) FROM event_occurrences o WHERE o.organization_id=:org AND o.series_id=:id
              AND o.capacity_override IS NULL AND
              (SELECT COALESCE(sum(seats),0) FROM event_registrations er WHERE er.occurrence_id=o.id AND er.status='confirmed') > :capacity
            """,Map.of("org",org,"id",id,"capacity",r.capacity()),Long.class);
        if(over!=null && over>0)throw new ApiException(HttpStatus.CONFLICT,"EVENT_FULL","Capacity is below existing registrations");
        return one("UPDATE event_series SET location_id=:loc,title=:title,description=:description,capacity=:capacity,price=:price,currency=:currency WHERE organization_id=:org AND id=:id RETURNING *",
            new MapSqlParameterSource().addValue("org",org).addValue("id",id).addValue("loc",r.locationId()).addValue("title",r.title().trim()).addValue("description",r.description()).addValue("capacity",r.capacity()).addValue("price",r.price()).addValue("currency",r.currency()==null?"CZK":r.currency()).getValues());
    }
    @Transactional
    public Object createOccurrence(long org,long series,EventDto.Occurrence r) {
        eventMode(org);
        one("SELECT id FROM event_series WHERE organization_id=:org AND id=:id AND active",Map.of("org",org,"id",series));
        if(!r.startsAt().isBefore(r.endsAt()) || !r.startsAt().isAfter(OffsetDateTime.now()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid event interval");
        return one("INSERT INTO event_occurrences(organization_id,series_id,starts_at,ends_at,capacity_override) VALUES(:org,:id,:start,:end,:capacity) RETURNING *",
            new MapSqlParameterSource().addValue("org",org).addValue("id",series).addValue("start",r.startsAt()).addValue("end",r.endsAt()).addValue("capacity",r.capacityOverride()).getValues());
    }
    public Object occurrences(long org) {
        eventMode(org);
        return db.queryForList("""
            SELECT o.id,o.series_id,o.starts_at,o.ends_at,o.status, s.title,s.location_id,
                   COALESCE(o.capacity_override,s.capacity) AS capacity,
                   COALESCE(sum(r.seats) FILTER (WHERE r.status='confirmed'),0) AS reserved,
                   COALESCE(o.capacity_override,s.capacity)-COALESCE(sum(r.seats) FILTER (WHERE r.status='confirmed'),0) AS available
            FROM event_occurrences o JOIN event_series s ON s.id=o.series_id
            LEFT JOIN event_registrations r ON r.occurrence_id=o.id
            WHERE o.organization_id=:org
            GROUP BY o.id,s.id ORDER BY o.starts_at DESC LIMIT 300
            """,Map.of("org",org));
    }
    public Object registrations(long org,long occurrence) {
        eventMode(org);
        one("SELECT id FROM event_occurrences WHERE organization_id=:org AND id=:id",Map.of("org",org,"id",occurrence));
        return db.queryForList("SELECT id,customer_name,customer_email,customer_phone,seats,price_amount,currency,status,created_at FROM event_registrations WHERE organization_id=:org AND occurrence_id=:id ORDER BY created_at DESC",Map.of("org",org,"id",occurrence));
    }
    @Transactional
    public Object cancelOccurrence(long org,long occurrence) {
        eventMode(org);
        var result=one("UPDATE event_occurrences SET status='cancelled' WHERE organization_id=:org AND id=:id RETURNING *",Map.of("org",org,"id",occurrence));
        db.update("UPDATE event_registrations SET status='cancelled',cancelled_at=now() WHERE organization_id=:org AND occurrence_id=:id AND status='confirmed'",Map.of("org",org,"id",occurrence));
        return result;
    }
    public Object publicEvents(UUID publicId) {
        var org=one("SELECT o.id,o.booking_enabled,c.booking_model FROM organizations o JOIN organization_booking_config c ON c.organization_id=o.id WHERE o.public_id=:uuid AND o.registration_status='active'",Map.of("uuid",publicId));
        if(!"EVENT".equals(org.get("booking_model"))) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Not an events organization");
        return db.queryForList("""
            SELECT o.id,o.starts_at,o.ends_at,s.title,s.description,s.price,s.currency,s.location_id,l.name AS location_name,
                   COALESCE(o.capacity_override,s.capacity) AS capacity,
                   COALESCE(sum(r.seats) FILTER (WHERE r.status='confirmed'),0) AS reserved,
                   COALESCE(o.capacity_override,s.capacity)-COALESCE(sum(r.seats) FILTER (WHERE r.status='confirmed'),0) AS available
            FROM event_occurrences o JOIN event_series s ON s.id=o.series_id
            JOIN locations l ON l.id=s.location_id
            LEFT JOIN event_registrations r ON r.occurrence_id=o.id
            WHERE o.organization_id=:org AND o.status='scheduled' AND o.starts_at>now()
              AND s.active AND l.active AND :enabled=true
            GROUP BY o.id,s.id,l.id ORDER BY o.starts_at LIMIT 200
            """,Map.of("org",org.get("id"),"enabled",org.get("booking_enabled")));
    }
    @Transactional
    public Object register(UUID publicId,long occurrence,EventDto.Registration r) {
        var event=one("""
            SELECT o.id,o.organization_id,o.starts_at,o.status,
                   COALESCE(o.capacity_override,s.capacity) AS capacity,s.price,s.currency
            FROM event_occurrences o JOIN event_series s ON s.id=o.series_id
            JOIN organizations org ON org.id=o.organization_id
            JOIN organization_booking_config c ON c.organization_id=org.id
            WHERE org.public_id=:uuid AND org.registration_status='active' AND org.booking_enabled
              AND c.booking_model='EVENT' AND c.setup_status='complete' AND s.active AND o.id=:id
            FOR UPDATE OF o
            """,Map.of("uuid",publicId,"id",occurrence));
        if(!"scheduled".equals(event.get("status")) || !((OffsetDateTime)event.get("starts_at")).isAfter(OffsetDateTime.now()))
            throw new ApiException(HttpStatus.CONFLICT,"SLOT_UNAVAILABLE","Event is closed");
        long org=((Number)event.get("organization_id")).longValue();
        Long taken=db.queryForObject("SELECT COALESCE(sum(seats),0) FROM event_registrations WHERE occurrence_id=:id AND status='confirmed'",Map.of("id",occurrence),Long.class);
        if(taken+r.seats()>((Number)event.get("capacity")).longValue())
            throw new ApiException(HttpStatus.CONFLICT,"EVENT_FULL","Not enough seats");
        String token=token();
        var created=one("""
            INSERT INTO event_registrations(organization_id,occurrence_id,customer_name,customer_email,customer_phone,seats,price_amount,currency,token_hash)
            VALUES(:org,:id,:name,:email,:phone,:seats,:price,:currency,:hash) RETURNING id,seats,status
            """,new MapSqlParameterSource().addValue("org",org).addValue("id",occurrence).addValue("name",r.customerName().trim()).addValue("email",r.customerEmail().trim()).addValue("phone",r.customerPhone()).addValue("seats",r.seats()).addValue("price",((java.math.BigDecimal)event.get("price")).multiply(java.math.BigDecimal.valueOf(r.seats()))).addValue("currency",event.get("currency")).addValue("hash",hash(token)).getValues());
        return Map.of("id",created.get("id"),"seats",created.get("seats"),"status",created.get("status"),"manageUrl",FrontendLinks.localized(FrontendLinks.base(publicUrl),"/events/manage/")+token);
    }
    public Object publicRegistration(String token) {
        return one("""
            SELECT r.id,r.customer_name,r.seats,r.status,o.starts_at,s.title
            FROM event_registrations r JOIN event_occurrences o ON o.id=r.occurrence_id
            JOIN event_series s ON s.id=o.series_id WHERE r.token_hash=:hash
            """,Map.of("hash",hash(token)));
    }
    @Transactional
    public Object cancelRegistration(String token) {
        // Lock the occurrence first: registration and cancellation serialize on the same capacity row.
        var target=one("SELECT occurrence_id FROM event_registrations WHERE token_hash=:hash",Map.of("hash",hash(token)));
        db.queryForList("SELECT id FROM event_occurrences WHERE id=:id FOR UPDATE",Map.of("id",target.get("occurrence_id")));
        return one("""
            UPDATE event_registrations r SET status='cancelled',cancelled_at=now()
            FROM event_occurrences o WHERE r.occurrence_id=o.id AND r.token_hash=:hash
            AND r.status='confirmed' AND o.starts_at>now() RETURNING r.id,r.status
            """,Map.of("hash",hash(token)));
    }
}
