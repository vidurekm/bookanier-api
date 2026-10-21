package online.bookanear.api.service;

import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Allocates concrete exclusive resources; caller holds the organization FOR UPDATE lock. */
@Service
public class ResourceAllocationService {
    private final NamedParameterJdbcTemplate db;

    public ResourceAllocationService(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    public record Allocation(long resourceId, Long memberId, String category, long typeId,
                             int quantity, Integer minCapacity, Long preferredResourceId) {}

    public List<Allocation> allocate(long org, long loc, long service,
                                     OffsetDateTime occupiedFrom, OffsetDateTime occupiedTo,
                                     BookingDto.CreateUniversalBooking request,
                                     Predicate<Long> staffAvailability) {
        var configured = db.queryForList("""
                SELECT req.resource_type_id, req.quantity, req.min_capacity, rt.category
                FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id = req.resource_type_id
                WHERE req.service_id = :service AND rt.organization_id = :org AND rt.active
                ORDER BY req.resource_type_id
                """, Map.of("org", org, "service", service));
        if (configured.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service has no active resource requirements");
        if (configured.size() > 1 && (request.quantity() != null || request.preferredResourceId() != null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity/preferredResourceId require a single resource type");
        if (request.partySize() != null && configured.stream().noneMatch(r -> "space".equals(r.get("category"))))
            throw new ApiException(HttpStatus.BAD_REQUEST, "partySize requires a space resource type");

        if(request.preferredMemberId()!=null && configured.stream().noneMatch(x->"staff".equals(x.get("category"))))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Member selection requires staff resources");
        List<Allocation> selected = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int staffCount = 0;
        for (var requirement : configured) {
            long typeId = ((Number) requirement.get("resource_type_id")).longValue();
            String category = (String) requirement.get("category");
            int minimumQuantity = ((Number) requirement.get("quantity")).intValue();
            int quantity = request.quantity() == null ? minimumQuantity : request.quantity();
            if (quantity < minimumQuantity)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity is below service minimum");
            if (quantity < 1 || quantity > 100)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity must be between 1 and 100");
            Integer minCapacity = requirement.get("min_capacity") == null ? null
                    : ((Number) requirement.get("min_capacity")).intValue();
            if ("space".equals(category) && request.partySize() != null)
                minCapacity = Math.max(minCapacity == null ? 1 : minCapacity, request.partySize());
            Long preferred = request.preferredResourceId();
            var params = new MapSqlParameterSource()
                    .addValue("org", org).addValue("loc", loc).addValue("type", typeId)
                    .addValue("capacity", minCapacity == null ? 1 : minCapacity)
                    .addValue("from", occupiedFrom).addValue("to", occupiedTo)
                    .addValue("preferred", preferred);
            var candidates = db.queryForList("""
                    SELECT r.id, r.member_id
                    FROM resources r
                    WHERE r.organization_id=:org AND r.location_id=:loc
                      AND r.resource_type_id=:type AND r.active
                      AND r.resource_state='available' AND r.capacity >= :capacity
                      AND (CAST(:preferred AS bigint) IS NULL OR r.id=:preferred)
                      AND NOT EXISTS (
                        SELECT 1 FROM reservation_resources rr
                        WHERE rr.resource_id=r.id AND rr.active
                          AND rr.occupied_period && tstzrange(CAST(:from AS timestamptz),CAST(:to AS timestamptz),'[)')
                      )
                    ORDER BY r.capacity, r.id
                    FOR UPDATE OF r
                    """, params);
            int found = 0;
            for (var candidate : candidates) {
                long resourceId = ((Number) candidate.get("id")).longValue();
                if (!used.add(resourceId)) continue;
                Long memberId = candidate.get("member_id") == null ? null
                        : ((Number) candidate.get("member_id")).longValue();
                if ("staff".equals(category)) {
                    if (memberId == null || (request.preferredMemberId()!=null && !request.preferredMemberId().equals(memberId)) || !staffAvailability.test(memberId)) continue;
                    staffCount++;
                    if (staffCount > 1)
                        throw new ApiException(HttpStatus.BAD_REQUEST, "Only one staff resource per reservation is supported");
                } else if (memberId != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Non-staff resource must not be linked to a member");
                }
                selected.add(new Allocation(resourceId, memberId, category, typeId,
                        quantity, minCapacity, preferred));
                if (++found == quantity) break;
            }
            if (found < quantity)
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_RESOURCES", "Not enough available resources of type " + typeId);
        }
        return selected;
    }

    public void persist(long org, long loc, long itemId, OffsetDateTime occupiedFrom,
                        OffsetDateTime occupiedTo, List<Allocation> allocations) {
        Set<Long> demands = new HashSet<>();
        for (Allocation a : allocations) {
            var p = new MapSqlParameterSource()
                    .addValue("org", org).addValue("loc", loc).addValue("item", itemId)
                    .addValue("resource", a.resourceId()).addValue("from", occupiedFrom)
                    .addValue("to", occupiedTo);
            db.update("""
                    INSERT INTO reservation_resources
                        (organization_id,location_id,reservation_item_id,resource_id,occupied_period)
                    VALUES (:org,:loc,:item,:resource,
                        tstzrange(CAST(:from AS timestamptz),CAST(:to AS timestamptz),'[)'))
                    """, p);
            if (demands.add(a.typeId())) {
                db.update("""
                        INSERT INTO reservation_item_resource_demands
                            (organization_id,location_id,reservation_item_id,resource_type_id,
                             quantity,min_capacity,preferred_resource_id)
                        VALUES (:org,:loc,:item,:type,:quantity,:capacity,:preferred)
                        """, new MapSqlParameterSource()
                        .addValue("org", org).addValue("loc", loc).addValue("item", itemId)
                        .addValue("type", a.typeId()).addValue("quantity", a.quantity())
                        .addValue("capacity", a.minCapacity()).addValue("preferred", a.preferredResourceId()));
            }
        }
    }
}
