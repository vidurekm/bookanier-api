package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/** Scoped permissions: workers granted can_manage_bookings may manage team bookings. */
@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/reservations")
public class BackofficeBookingManagementController {
    private final BookingService booking;
    private final NamedParameterJdbcTemplate db;
    public BackofficeBookingManagementController(BookingService booking,NamedParameterJdbcTemplate db) {
        this.booking=booking;this.db=db;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication)")
    public Object create(@PathVariable long org,@Valid @RequestBody BookingDto.CreateUniversalBooking request) {
        if (request.organizationId()!=org) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Wrong organization");
        Long requirementCount=db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements r JOIN services s ON s.id=r.service_id
                WHERE s.organization_id=:org AND s.id=:service
                """,Map.of("org",org,"service",request.serviceId()),Long.class);
        if (requirementCount==null||requirementCount==0) {
            if (request.preferredMemberId()==null)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Select a worker");
            return booking.create(new BookingDto.CreateBooking(request.organizationId(),request.locationId(),
                    request.serviceId(),request.preferredMemberId(),request.startsAt(),
                    request.customerName(),request.customerEmail(),request.customerPhone()));
        }
        return booking.createUniversal(request);
    }

    @PutMapping("/{id}/reschedule")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication)")
    public Object reschedule(@PathVariable long org,@PathVariable long id,
                              @Valid @RequestBody BookingDto.Reschedule request) {
        return booking.rescheduleBackoffice(org,id,request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@organizationSecurity.canManageBookings(#p0, authentication)")
    @Transactional
    public Object changeStatus(@PathVariable long org,@PathVariable long id,@RequestBody StatusChange change) {
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
