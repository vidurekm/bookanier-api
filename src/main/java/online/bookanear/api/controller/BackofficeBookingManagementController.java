package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.BookingService;
import online.bookanear.api.config.OrganizationSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

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
    public BackofficeBookingManagementController(BookingService booking,NamedParameterJdbcTemplate db,
                                                OrganizationSecurity security) {
        this.booking=booking;this.db=db;this.security=security;
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

    @PutMapping("/{id}/status")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication) or @organizationSecurity.canManageOwnBookings(#p0, authentication)")
    @Transactional
    public Object changeStatus(@PathVariable long org,@PathVariable long id,@RequestBody StatusChange change,
                               Authentication authentication) {
        if (!security.canManageReservation(org, id, authentication))
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You may only manage your own bookings");
        // Explicit state machine; never resurrect a cancelled/completed booking.
        var rows=db.queryForList("SELECT status FROM reservations WHERE organization_id=:org AND id=:id FOR UPDATE",
                Map.of("org",org,"id",id));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Reservation not found");
        String old=rows.getFirst().get("status").toString();
        String next=change.status();
        boolean valid=("pending".equals(old) && Set.of("confirmed","cancelled").contains(next)) ||
              ("confirmed".equals(old) && Set.of("completed","no_show","cancelled").contains(next));
        if (!valid) throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","This status transition is unavailable");
        db.update("UPDATE reservations SET status=:status WHERE organization_id=:org AND id=:id",
                Map.of("org",org,"id",id,"status",next));
        return Map.of("id",id,"status",next);
    }
    public record StatusChange(String status) {}
}
