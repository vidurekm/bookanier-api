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
                                     Predicate<Long> staffAvailability,
                                     boolean enforceCustomerChoices) {
        var choices = db.queryForMap("""
                SELECT customer_can_choose_staff,customer_can_choose_resource
                FROM services WHERE organization_id=:org AND id=:service
                """, Map.of("org",org,"service",service));
        var preferredMembers = new ArrayList<Long>();
        if (request.preferredMemberIds() != null) preferredMembers.addAll(request.preferredMemberIds());
        var excludedMembers = request.excludedMemberIds() == null
                ? Set.<Long>of() : new HashSet<>(request.excludedMemberIds());
        if (request.preferredMemberId() != null) {
            if (!preferredMembers.isEmpty() && !preferredMembers.equals(List.of(request.preferredMemberId())))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Conflicting staff selections");
            if (preferredMembers.isEmpty()) preferredMembers.add(request.preferredMemberId());
        }
        if (new HashSet<>(preferredMembers).size() != preferredMembers.size())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Staff selections must be unique");
        if (enforceCustomerChoices && !preferredMembers.isEmpty()
                && !Boolean.TRUE.equals(choices.get("customer_can_choose_staff")))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Customer staff selection is disabled for this service");
        if (enforceCustomerChoices && request.preferredResourceId() != null
                && !Boolean.TRUE.equals(choices.get("customer_can_choose_resource")))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Customer resource selection is disabled for this service");
        var configured = db.queryForList("""
                SELECT req.resource_type_id, req.quantity, req.min_capacity, rt.category
                FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id = req.resource_type_id
                WHERE req.service_id = :service AND rt.organization_id = :org AND rt.active
                ORDER BY req.resource_type_id
                """, Map.of("org", org, "service", service));
        if (configured.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service has no active resource requirements");
        long nonStaffTypes=configured.stream().filter(r->!"staff".equals(r.get("category"))).count();
        if (request.preferredResourceId() != null && nonStaffTypes != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Resource selection requires exactly one non-staff resource type");
        if (request.preferredResourceId() != null && configured.stream()
                .filter(r->!"staff".equals(r.get("category")))
                .anyMatch(r->((Number)r.get("quantity")).intValue()!=1))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Resource selection requires a single resource unit");
        if (request.quantity() != null && nonStaffTypes != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity requires a single non-staff resource type");
        if (request.partySize() != null && configured.stream().noneMatch(r -> "space".equals(r.get("category"))))
            throw new ApiException(HttpStatus.BAD_REQUEST, "partySize requires a space resource type");

        if(!preferredMembers.isEmpty() && configured.stream().noneMatch(x->"staff".equals(x.get("category"))))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Member selection requires staff resources");
        List<Allocation> selected = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        Set<Long> selectedMembers = new HashSet<>();
        int selectedStaffCount = 0;
        for (var requirement : configured) {
            long typeId = ((Number) requirement.get("resource_type_id")).longValue();
            String category = (String) requirement.get("category");
            int minimumQuantity = ((Number) requirement.get("quantity")).intValue();
            int quantity = request.quantity() == null || "staff".equals(category)
                    ? minimumQuantity : request.quantity();
            if (quantity < minimumQuantity)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity is below service minimum");
            if (quantity < 1 || quantity > 100)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Quantity must be between 1 and 100");
            if ("staff".equals(category) && enforceCustomerChoices
                    && Boolean.TRUE.equals(choices.get("customer_can_choose_staff"))
                    && preferredMembers.size() != quantity)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                        "Select all required staff members for this service");
            if ("staff".equals(category) && !preferredMembers.isEmpty()
                    && preferredMembers.size() != quantity)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                        "Selected staff count does not match the service requirement");
            Integer minCapacity = requirement.get("min_capacity") == null ? null
                    : ((Number) requirement.get("min_capacity")).intValue();
            if ("space".equals(category) && request.partySize() != null)
                minCapacity = Math.max(minCapacity == null ? 1 : minCapacity, request.partySize());
            Long preferred = !"staff".equals(category) ? request.preferredResourceId() : null;
            var params = new MapSqlParameterSource()
                    .addValue("org", org).addValue("loc", loc).addValue("type", typeId).addValue("service",service)
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
                      AND (r.member_id IS NULL OR EXISTS (
                        SELECT 1 FROM worker_services ws
                        WHERE ws.organization_id=r.organization_id AND ws.member_id=r.member_id
                          AND ws.service_id=:service AND ws.active))
                      AND NOT EXISTS (
                        SELECT 1 FROM reservation_resources rr
                        WHERE rr.organization_id=:org AND rr.resource_id=r.id AND rr.active
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
                    if (memberId == null || (!preferredMembers.isEmpty() && !preferredMembers.contains(memberId))
                            || excludedMembers.contains(memberId)
                            || !staffAvailability.test(memberId)) continue;
                    if (!selectedMembers.add(memberId)) continue;
                    selectedStaffCount++;
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
        if (!preferredMembers.isEmpty() && selectedStaffCount != preferredMembers.size())
            throw new ApiException(HttpStatus.CONFLICT,"INSUFFICIENT_RESOURCES","One or more selected staff members are unavailable");
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
