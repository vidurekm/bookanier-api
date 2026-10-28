package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.BookingService;
import online.bookanear.api.service.PublicCatalogService;
import online.bookanear.api.service.ReservationNotificationService;
import online.bookanear.api.config.OrganizationSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scoped permissions: workers granted can_manage_bookings may manage team bookings. */
@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/reservations")
public class BackofficeBookingManagementController {
    private final BookingService booking;
    private final NamedParameterJdbcTemplate db;
    private final OrganizationSecurity security;
    private final ReservationNotificationService notifications;
    private final PublicCatalogService catalog;
    public BackofficeBookingManagementController(BookingService booking,NamedParameterJdbcTemplate db,
                                                OrganizationSecurity security,
                                                ReservationNotificationService notifications,
                                                PublicCatalogService catalog) {
        this.booking=booking;this.db=db;this.security=security;this.notifications=notifications;this.catalog=catalog;
    }

    @GetMapping("/slots")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public Map<String, Object> slots(@PathVariable long org, @RequestParam long locationId,
                                     @RequestParam long serviceId, @RequestParam LocalDate date,
                                     @RequestParam(required = false) Integer partySize,
                                     @RequestParam(required = false) Integer quantity,
                                     @RequestParam(required = false) List<Long> preferredMemberIds,
                                     @RequestParam(required = false) Long preferredResourceId) {
        return catalog.backofficeSlots(org, locationId, serviceId, date, partySize, quantity,
                preferredMemberIds, preferredResourceId);
    }

    @GetMapping("/slots/unavailable-days")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public List<String> unavailableDays(@PathVariable long org, @RequestParam long locationId,
                                        @RequestParam long serviceId, @RequestParam LocalDate from,
                                        @RequestParam LocalDate to,
                                        @RequestParam(required = false) Integer partySize,
                                        @RequestParam(required = false) Integer quantity) {
        return catalog.backofficeUnavailableDays(org, locationId, serviceId, from, to, partySize, quantity);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public Object create(@PathVariable long org,@Valid @RequestBody BookingDto.CreateUniversalBooking request,
                         Authentication authentication) {
        if (request.organizationId()!=org) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Wrong organization");
        boolean canWrite = security.canWrite(org, authentication);
        boolean ownOnly = !canWrite && security.canManageOwnBookings(org, authentication);
        boolean manageColleagues = !canWrite && security.canManageBookings(org, authentication);
        Long ownMemberId = !canWrite ? security.ownMemberId(org, authentication) : null;
        boolean choseOtherMember = request.preferredMemberIds() != null
                && ownOnly && ownMemberId != null
                && request.preferredMemberIds().stream().anyMatch(id -> !ownMemberId.equals(id));
        if (ownOnly && (ownMemberId == null
                || request.preferredMemberId() != null && !ownMemberId.equals(request.preferredMemberId())
                || choseOtherMember))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only create bookings assigned to yourself");
        if (manageColleagues && (ownMemberId == null
                || ownMemberId.equals(request.preferredMemberId())
                || request.preferredMemberIds() != null && request.preferredMemberIds().contains(ownMemberId)))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only create bookings assigned to colleagues");
        Long requirementCount=db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements r JOIN services s ON s.id=r.service_id
                WHERE s.organization_id=:org AND s.id=:service
                """,Map.of("org",org,"service",request.serviceId()),Long.class);
        if (requirementCount==null||requirementCount==0) {
            Long memberId = ownOnly ? ownMemberId : request.preferredMemberId();
            if (memberId == null && request.preferredMemberIds() != null
                    && request.preferredMemberIds().size() == 1)
                memberId = request.preferredMemberIds().getFirst();
            if (memberId==null)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Select a worker");
            if (manageColleagues && ownMemberId.equals(memberId))
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only create bookings assigned to colleagues");
            return booking.create(new BookingDto.CreateBooking(request.organizationId(),request.locationId(),
                    request.serviceId(),memberId,request.startsAt(),
                    request.customerName(),request.customerEmail(),request.customerPhone()),false);
        }
        if (!ownOnly && !manageColleagues) return booking.createUniversal(request, false);
        if (manageColleagues) {
            var excludedMemberIds = new ArrayList<Long>();
            if (request.excludedMemberIds() != null) excludedMemberIds.addAll(request.excludedMemberIds());
            if (!excludedMemberIds.contains(ownMemberId)) excludedMemberIds.add(ownMemberId);
            return booking.createUniversal(new BookingDto.CreateUniversalBooking(request.organizationId(),
                    request.locationId(), request.serviceId(), request.startsAt(), request.endsAt(),
                    request.partySize(), request.quantity(), request.preferredResourceId(),
                    request.preferredMemberId(), request.preferredMemberIds(), request.customerName(),
                    request.customerEmail(), request.customerPhone(), excludedMemberIds), false);
        }
        return booking.createUniversal(new BookingDto.CreateUniversalBooking(request.organizationId(),
                request.locationId(), request.serviceId(), request.startsAt(), request.endsAt(),
                request.partySize(), request.quantity(), request.preferredResourceId(), ownMemberId,
                List.of(ownMemberId), request.customerName(), request.customerEmail(), request.customerPhone()), false);
    }

    @PutMapping("/{id}/reschedule")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public Object reschedule(@PathVariable long org,@PathVariable long id,
                              @Valid @RequestBody BookingDto.Reschedule request, Authentication authentication) {
        if (!security.canManageReservation(org, id, authentication))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only manage your own bookings");
        return booking.rescheduleBackoffice(org,id,request);
    }

    @GetMapping("/{id}/availability")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public Object rescheduleAvailability(@PathVariable long org, @PathVariable long id,
                                         @RequestParam LocalDate date,
                                         @RequestParam(required = false) java.util.List<Long> preferredMemberIds,
                                         @RequestParam(required = false) Long preferredResourceId,
                                         Authentication authentication) {
        if (!security.canManageReservation(org, id, authentication))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only manage your own bookings");
        return booking.rescheduleAvailability(org, id, date, preferredMemberIds, preferredResourceId);
    }

    @GetMapping("/{id}/reschedule-options")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    public Object rescheduleOptions(@PathVariable long org, @PathVariable long id, Authentication authentication) {
        if (!security.canManageReservation(org, id, authentication))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only manage your own bookings");
        return booking.rescheduleOptions(org, id);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    @Transactional
    public Object changeStatus(@PathVariable long org,@PathVariable long id,@RequestBody StatusChange change,
                               Authentication authentication) {
        if (!security.canManageReservation(org, id, authentication))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only manage your own bookings");
        db.queryForList("SELECT id FROM organizations WHERE id=:org FOR UPDATE", Map.of("org", org));
        var rows=db.queryForList("SELECT status FROM reservations WHERE organization_id=:org AND id=:id FOR UPDATE",
                Map.of("org",org,"id",id));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Reservation not found");
        String old=rows.getFirst().get("status").toString();
        String next=change.status();
        boolean valid=next != null && (("pending".equals(old) && Set.of("confirmed","cancelled").contains(next)) ||
              ("confirmed".equals(old) && Set.of("completed","no_show","cancelled").contains(next)) ||
              ("cancelled".equals(old) && "confirmed".equals(next)) ||
              (Set.of("completed","no_show").contains(old)
                      && Set.of("confirmed","completed","no_show").contains(next)));
        if (!valid) throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","This status transition is unavailable");
        if ("cancelled".equals(old)) {
            var params = Map.of("org", org, "id", id);
            Long conflicts = db.queryForObject("""
                    WITH assigned AS (
                      SELECT ri.id,ri.member_id,ri.occupied_period
                      FROM reservation_items ri
                      WHERE ri.organization_id=:org AND ri.reservation_id=:id AND ri.member_id IS NOT NULL
                      UNION
                      SELECT ri.id,res.member_id,rr.occupied_period
                      FROM reservation_items ri
                      JOIN reservation_resources rr ON rr.organization_id=ri.organization_id
                        AND rr.reservation_item_id=ri.id
                      JOIN resources res ON res.organization_id=rr.organization_id AND res.id=rr.resource_id
                      WHERE ri.organization_id=:org AND ri.reservation_id=:id AND res.member_id IS NOT NULL
                    )
                    SELECT COUNT(*) FROM assigned a WHERE
                      EXISTS (SELECT 1 FROM reservation_items other
                        WHERE other.organization_id=:org AND other.reservation_id<>:id
                          AND other.member_id=a.member_id AND other.item_status='active'
                          AND other.occupied_period && a.occupied_period)
                      OR EXISTS (SELECT 1 FROM reservation_resources rr
                        JOIN resources res ON res.organization_id=rr.organization_id AND res.id=rr.resource_id
                        JOIN reservation_items other ON other.organization_id=rr.organization_id
                          AND other.id=rr.reservation_item_id
                        WHERE rr.organization_id=:org AND other.reservation_id<>:id AND rr.active
                          AND res.member_id=a.member_id AND rr.occupied_period && a.occupied_period)
                    """, params, Long.class);
            if (conflicts != null && conflicts > 0)
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "The worker is no longer available");
            db.update("""
                    UPDATE reservation_items SET item_status='active'
                    WHERE organization_id=:org AND reservation_id=:id
                    """, params);
            db.update("""
                    UPDATE reservation_resources SET active=true
                    WHERE organization_id=:org AND reservation_item_id IN (
                      SELECT id FROM reservation_items WHERE organization_id=:org AND reservation_id=:id)
                    """, params);
        }
        db.update("UPDATE reservations SET status=:status WHERE organization_id=:org AND id=:id",
                Map.of("org",org,"id",id,"status",next));
        if ("cancelled".equals(next)) notifications.sendCancelled(org,id);
        return Map.of("id",id,"status",next);
    }
    public record StatusChange(String status) {}
}
