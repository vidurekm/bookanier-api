package online.bookanear.api.controller;

import online.bookanear.api.service.BackofficeService;
import online.bookanear.api.dto.BackofficeDto;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Backoffice API. Every mapped endpoint has tenant-aware method authorization.
 */
@RestController
@RequestMapping("/api/v1/backoffice/organizations")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "05 - Backoffice", description = "Authenticated organization administration, catalog, schedules and reservations.")
public class BackofficeController {
    private final BackofficeService service;
    public BackofficeController(BackofficeService service) { this.service = service; }

    @GetMapping("/{org}/resource-types")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object resourceTypes(@PathVariable long org) { return service.resourceTypes(org); }

    @PostMapping("/{org}/resource-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object createResourceType(@PathVariable long org,
                                     @Valid @RequestBody BackofficeDto.ResourceTypeCreate request) {
        return service.createResourceType(org, request);
    }

    @PutMapping("/{org}/resource-types/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object updateResourceType(@PathVariable long org, @PathVariable long id,
                                     @Valid @RequestBody BackofficeDto.ResourceTypeUpdate request) {
        return service.updateResourceType(org, id, request);
    }

    @DeleteMapping("/{org}/resource-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public void deleteResourceType(@PathVariable long org, @PathVariable long id) {
        service.deleteResourceType(org, id);
    }

    @GetMapping("/{org}/locations/{loc}/resources")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object resources(@PathVariable long org, @PathVariable long loc) {
        return service.resources(org, loc);
    }

    @PostMapping("/{org}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object createResource(@PathVariable long org,
                                 @Valid @RequestBody BackofficeDto.ResourceCreate request) {
        return service.createResource(org, request);
    }

    @PutMapping("/{org}/locations/{loc}/resources/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object updateResource(@PathVariable long org, @PathVariable long loc, @PathVariable long id,
                                 @Valid @RequestBody BackofficeDto.ResourceUpdate request) {
        return service.updateResource(org, loc, id, request);
    }

    @DeleteMapping("/{org}/locations/{loc}/resources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public void deleteResource(@PathVariable long org, @PathVariable long loc, @PathVariable long id) {
        service.deleteResource(org, loc, id);
    }

    @GetMapping("/{org}/services/{id}/resource-requirements")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object resourceRequirements(@PathVariable long org, @PathVariable long id) {
        return service.resourceRequirements(org, id);
    }

    @GetMapping("/{org}/services/{id}/booking-options")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object serviceBookingOptions(@PathVariable long org, @PathVariable long id) {
        return service.serviceBookingOptions(org, id);
    }

    @PutMapping("/{org}/services/{id}/booking-options")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object updateServiceBookingOptions(@PathVariable long org, @PathVariable long id,
            @Valid @RequestBody BackofficeDto.ServiceBookingOptions request) {
        return service.updateServiceBookingOptions(org, id, request);
    }

    @PutMapping("/{org}/services/{id}/resource-requirements")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object replaceResourceRequirements(@PathVariable long org, @PathVariable long id,
            @Valid @RequestBody BackofficeDto.ResourceRequirements request) {
        return service.replaceResourceRequirements(org, id, request);
    }

    @GetMapping("/{org}/services/{id}")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get service", description = "Get a service belonging to the selected organization.")
    public Object service(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.service(org, id);
    }

    @PutMapping("/{org}/services/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Update service", description = "Update the service name, description and active status.")
    public Object updateService(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Valid @RequestBody BackofficeDto.ServiceUpsert r) {
        return service.updateService(org, id, r);
    }

    @DeleteMapping("/{org}/services/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Delete unused service", description = "Permanently delete a service only if it has never been used by a reservation or waitlist entry.")
    public void deleteService(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        service.deleteService(org, id);
    }

    @GetMapping("/{org}/services/{id}/versions")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "List service versions", description = "List effective-dated versions of a service, including duration and pricing.")
    public Object versions(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.versions(org, id);
    }

    @PostMapping("/{org}/services/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Create service version", description = "Create a new effective-dated service duration and price version.")
    public Object createVersion(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Valid @RequestBody BackofficeDto.ServiceVersionCreate r) {
        return service.createVersion(org, id, r);
    }

    @GetMapping("/{org}/locations/{id}")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get location", description = "Get a location belonging to the organization.")
    public Object location(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.location(org, id);
    }

    @PutMapping("/{org}/locations/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Update location", description = "Update location details, time zone and active status.")
    public Object updateLocation(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Valid @RequestBody BackofficeDto.LocationUpsert r) {
        return service.updateLocation(org, id, r);
    }

    @DeleteMapping("/{org}/locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Archive location", description = "Archive a location after all future active reservations have been cancelled. Historical records are retained.")
    public void archiveLocation(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        service.archiveLocation(org, id);
    }

    @GetMapping("/{org}/members/{id}")
    @PreAuthorize("@organizationSecurity.canAccessMember(#p0, #p1, authentication)")
    @Operation(summary = "Get organization member", description = "Get an organization member, subject to role-based access.")
    public Object member(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.member(org, id);
    }

    @PutMapping("/{org}/members/{id}")
    @PreAuthorize("@organizationSecurity.isOwner(#p0, authentication)")
    @Operation(summary = "Update organization member", description = "Update member details, role and active status. Owner permission is required.")
    public Object updateMember(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Valid @RequestBody BackofficeDto.MemberUpsert r) {
        return service.updateMember(org, id, r);
    }

    @DeleteMapping("/{org}/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.isOwner(#p0, authentication)")
    @Operation(summary = "Remove worker from organization", description = "Archive a worker membership after future active reservations have been cancelled. The user's account and historical records are retained.")
    public void archiveMember(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the organization member.") @PathVariable long id) {
        service.archiveMember(org, id);
    }

    @GetMapping("/{org}/members/{id}/services")
    @PreAuthorize("@organizationSecurity.canAccessMember(#p0, #p1, authentication)")
    @Operation(summary = "List member services", description = "List services assigned to an organization member.")
    public Object memberServices(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.memberServices(org, id);
    }

    @PutMapping("/{org}/members/{id}/services/{serviceId}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Assign or unassign member service", description = "Set whether a service is assigned to the specified member.")
    public Object assignService(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the service.") @PathVariable long serviceId, @RequestBody BackofficeDto.Assignment r) {
        return service.assignService(org, id, serviceId, r);
    }

    @GetMapping("/{org}/customers/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Get customer", description = "Get a customer belonging to the organization.")
    public Object customer(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.customer(org, id);
    }

    @PutMapping("/{org}/customers/{id}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Update customer", description = "Update customer contact details and internal notes.")
    public Object updateCustomer(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Valid @RequestBody BackofficeDto.CustomerUpsert r) {
        return service.updateCustomer(org, id, r);
    }

    @DeleteMapping("/{org}/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Delete customer", description = "Delete a customer while preserving reservation history.")
    public void deleteCustomer(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the customer.") @PathVariable long id) {
        service.deleteCustomer(org, id);
    }

    @GetMapping("/{org}/reservations/{id}")
    @PreAuthorize("@organizationSecurity.canAccessReservation(#p0, #p1, authentication)")
    @Operation(summary = "Get reservation", description = "Get an organization reservation, subject to reservation-level access rules.")
    public Object reservation(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, Authentication authentication) {
        return service.reservation(org, id, authentication);
    }

    @GetMapping("/{org}/locations/{id}/schedule")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get location schedule", description = "Read schedule rules for a location, optionally limited to a date range.")
    public Object schedule(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, Authentication authentication,
            @Parameter(description = "Start of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.LocalDate from,
            @Parameter(description = "End of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.LocalDate to) {
        return service.schedule(org, id, authentication, from, to);
    }

    @GetMapping("/{org}/locations/{id}/opening-hours")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get weekly opening hours", description = "Read the regular weekly opening intervals of a location.")
    public Object openingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id) {
        return service.openingHours(org, id);
    }

    @PutMapping("/{org}/locations/{id}/opening-hours")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Replace weekly opening hours", description = "Replace the regular opening intervals. Use dryRun to preview conflicts; explicit confirmation is required to cancel affected reservations.")
    public Object replaceOpeningHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id,
            @Valid @RequestBody BackofficeDto.WeeklySchedule request,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.replaceWeeklySchedule(org, id, null, "open", request, dryRun, cancelConflictingReservations);
    }

    @GetMapping("/{org}/locations/{id}/members/{memberId}/working-hours")
    @PreAuthorize("@organizationSecurity.canAccessMember(#p0, #p2, authentication)")
    @Operation(summary = "Get weekly working hours", description = "Read the regular weekly working intervals for a member at a location.")
    public Object workingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the organization member.") @PathVariable long memberId) {
        return service.workingHours(org, id, memberId);
    }

    @PutMapping("/{org}/locations/{id}/members/{memberId}/working-hours")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Replace weekly working hours", description = "Replace a member's regular working intervals. Preview conflicts before confirming cancellations.")
    public Object replaceWorkingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the organization member.") @PathVariable long memberId,
            @Valid @RequestBody BackofficeDto.WeeklySchedule request,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.replaceWeeklySchedule(org, id, memberId, "working", request, dryRun, cancelConflictingReservations);
    }

    @GetMapping("/{org}/locations/{id}/opening-hours/dates")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get dated opening hours", description = "Read date-specific opening-hour overrides within the requested date range.")
    public Object datedOpeningHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id,
            @Parameter(description = "Start of the filter range (inclusive); use ISO 8601 format.") @RequestParam java.time.LocalDate from, @Parameter(description = "End of the filter range (inclusive); use ISO 8601 format.") @RequestParam java.time.LocalDate to) {
        return service.datedSchedule(org, id, null, "open", from, to);
    }

    @PutMapping("/{org}/locations/{id}/opening-hours/dates")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Set dated opening hours", description = "Replace the opening-hour intervals for specified calendar dates. Empty intervals mean closed for that date.")
    public Object putDatedOpeningHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id,
            @Valid @RequestBody BackofficeDto.DatedSchedule request,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.replaceDatedSchedule(org, id, null, "open", request, dryRun, cancelConflictingReservations);
    }

    @GetMapping("/{org}/locations/{id}/members/{memberId}/working-hours/dates")
    @PreAuthorize("@organizationSecurity.canAccessMember(#p0, #p2, authentication)")
    @Operation(summary = "Get dated working hours", description = "Read date-specific working-hour overrides for a member within the requested date range.")
    public Object datedWorkingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the organization member.") @PathVariable long memberId,
            @Parameter(description = "Start of the filter range (inclusive); use ISO 8601 format.") @RequestParam java.time.LocalDate from, @Parameter(description = "End of the filter range (inclusive); use ISO 8601 format.") @RequestParam java.time.LocalDate to) {
        return service.datedSchedule(org, id, memberId, "working", from, to);
    }

    @PutMapping("/{org}/locations/{id}/members/{memberId}/working-hours/dates")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Set dated working hours", description = "Replace the member's shifts for specified calendar dates. Empty intervals mean a day off.")
    public Object putDatedWorkingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the organization member.") @PathVariable long memberId,
            @Valid @RequestBody BackofficeDto.DatedSchedule request,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.replaceDatedSchedule(org, id, memberId, "working", request, dryRun, cancelConflictingReservations);
    }

    @DeleteMapping("/{org}/locations/{id}/members/{memberId}/working-hours/dates/{date}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Delete dated working-hours override", description = "Remove the date-specific working-hours override and revert to the regular schedule for that day.")
    public Object deleteDatedWorkingHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id, @Parameter(description = "Identifier of the organization member.") @PathVariable long memberId,
            @Parameter(description = "Calendar date in ISO 8601 format (YYYY-MM-DD).") @PathVariable java.time.LocalDate date,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.deleteDatedSchedule(org, id, memberId, "working", date, dryRun, cancelConflictingReservations);
    }

    @DeleteMapping("/{org}/locations/{id}/opening-hours/dates/{date}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Delete dated opening-hours override", description = "Remove the date-specific opening-hours override and revert to the regular schedule for that day.")
    public Object deleteDatedOpeningHours(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Internal identifier of the resource in the URL.") @PathVariable long id,
            @Parameter(description = "Calendar date in ISO 8601 format (YYYY-MM-DD).") @PathVariable java.time.LocalDate date,
            @Parameter(description = "If true, preview schedule conflicts without saving any changes.") @RequestParam(defaultValue="false") boolean dryRun,
            @Parameter(description = "If true, explicitly confirm cancellation of eligible conflicting reservations.") @RequestParam(defaultValue="false") boolean cancelConflictingReservations) {
        return service.deleteDatedSchedule(org, id, null, "open", date, dryRun, cancelConflictingReservations);
    }

    @PostMapping("/{org}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Add recurring schedule rule", description = "Add a recurring working-hours rule for a member at a location.")
    public Object addSchedule(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Valid @RequestBody BackofficeDto.ScheduleCreate r) {
        return service.addSchedule(org, r);
    }

    @GetMapping("/{org}/absences")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "List availability overrides", description = "Search and paginate availability exceptions, such as absences and extra availability.")
    public Object absences(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, Authentication authentication,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Start of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.OffsetDateTime from,
            @Parameter(description = "End of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.OffsetDateTime to,
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q, @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="id,desc") String sort) {
        return service.absences(org, authentication, page, size, from, to, q, sort);
    }

    @PostMapping("/{org}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canCreateAbsence(#p0, #p1.memberId(), authentication)")
    @Operation(summary = "Create availability override", description = "Create a time-bounded unavailable or available interval for a member or location.")
    public Object addAbsence(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Valid @RequestBody BackofficeDto.AbsenceCreate r) {
        return service.addAbsence(org, r);
    }



    @GetMapping("/{org}")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Get organization", description = "Get an organization by its internal identifier.")
    public Object getOrganization(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org) {
        return service.getOrganization(org);
    }

    @PutMapping("/{org}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Update organization", description = "Update the organization profile, time zone and booking availability.")
    public Object updateOrganization(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org,

                                     @Valid @RequestBody BackofficeDto.OrganizationUpdate r) {
        return service.updateOrganization(org, r);
    }

    @DeleteMapping("/{org}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.isOwner(#p0, authentication)")
    @Operation(summary = "Delete organization", description = "Permanently delete an organization and its data. Owner permission is required.")
    public void deleteOrganization(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org) {
        service.deleteOrganization(org);
    }

    @PostMapping("/{org}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Create location", description = "Add a location to the organization.")
    public Object createLocation(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Valid @RequestBody BackofficeDto.CreateLocation r) {
        return service.createLocation(org, r);
    }

    @PostMapping("/{org}/services")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Create service", description = "Add a service to the organization.")
    public Object createService(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Valid @RequestBody BackofficeDto.CreateService r) {
        return service.createService(org, r);
    }

    @GetMapping("/{org}/customers")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Search customers", description = "Filter, sort and paginate the organization's customers.")
    public Object customers(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name, @Parameter(description = "Filter by email address (case-insensitive partial match).") @RequestParam(required=false) String email,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="id,desc") String sort,
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q) {
        return service.customers(org, name, email, page, size, sort, q);
    }

    @GetMapping("/{org}/booking-customers")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Search booking customers", description = "Group reservation contacts by normalized email and keep reservations without email separate.")
    public Object bookingCustomers(@PathVariable long org,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page,
            @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Keyword search across customer name, email and phone.") @RequestParam(required=false) String q,
            @Parameter(description = "Sort by one supported field and direction.") @RequestParam(defaultValue="lastReservationAt,desc") String sort) {
        return service.bookingCustomers(org, page, size, q, sort);
    }

    @GetMapping("/{org}/booking-customers/{id}/reservations")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "List booking customer reservations", description = "List reservations for the normalized email represented by this customer's first reservation.")
    public Object bookingCustomerReservations(@PathVariable long org, @PathVariable long id) {
        return service.bookingCustomerReservations(org, id);
    }

    @PostMapping("/{org}/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Create customer", description = "Create a customer record within the organization.")
    public Object createCustomer(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Valid @RequestBody BackofficeDto.CreateCustomer r) {
        return service.createCustomer(org, r);
    }

    @GetMapping("/{org}/reservations")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    @Operation(summary = "Search reservations", description = "Filter reservations by location, status, customer, worker, or resource and paginate results. Worker visibility is limited by authorization.")
    public Object reservations(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org, @Parameter(description = "Filter by location identifier.") @RequestParam(required=false) Long locationId, Authentication authentication,
            @Parameter(description = "Filter by status.") @RequestParam(required=false) String status,
            @Parameter(description = "Include only reservations with an active booking item starting in the future.") @RequestParam(defaultValue="false") boolean upcomingOnly,
            @Parameter(description = "Filter by assigned worker identifier.") @RequestParam(required=false) Long memberId,
            @Parameter(description = "Filter by assigned non-staff resource identifier.") @RequestParam(required=false) Long resourceId,
            @Parameter(description = "Start of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.OffsetDateTime from,
            @Parameter(description = "End of the filter range (inclusive); use ISO 8601 format.") @RequestParam(required=false) java.time.OffsetDateTime to,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Search by customer name or email.") @RequestParam(required=false) String q, @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="createdAt,desc") String sort) {
        return service.reservations(org, locationId, authentication, status, upcomingOnly, memberId, resourceId, from, to, page, size, q, sort);
    }

    @GetMapping("/{org}/members")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Search organization members", description = "Filter, sort and paginate members of the organization.")
    public Object members(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name, @Parameter(description = "Filter by organization member role (owner, admin or worker).") @RequestParam(required=false) String role,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q, @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="id,asc") String sort) {
        return service.members(org, name, role, page, size, q, sort);
    }

    @GetMapping("/{org}/services")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Search services", description = "Filter, sort and paginate services in the organization.")
    public Object services(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name, @Parameter(description = "Filter by active/inactive status.") @RequestParam(required=false) Boolean active,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q, @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="name,asc") String sort) {
        return service.services(org, name, active, page, size, q, sort);
    }

    @GetMapping("/{org}/locations")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    @Operation(summary = "Search locations", description = "Filter, sort and paginate locations in the organization.")
    public Object locations(@Parameter(description = "Internal identifier of the organization; access is restricted to the current tenant.") @PathVariable long org,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name, @Parameter(description = "Filter by active/inactive status.") @RequestParam(required=false) Boolean active,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page, @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q, @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="name,asc") String sort) {
        return service.locations(org, name, active, page, size, q, sort);
    }

    @GetMapping("/users")
    @PreAuthorize("@organizationSecurity.isSuperadmin(authentication)")
    @Operation(summary = "Search platform users", description = "Filter, sort and paginate all platform users. Superadmin permission is required.")
    public Object users(
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name,
            @Parameter(description = "Filter by email address (case-insensitive partial match).") @RequestParam(required=false) String email,
            @Parameter(description = "Filter by whether the email address has been verified.") @RequestParam(required=false) Boolean verified,
            @Parameter(description = "Filter by platform superadmin flag.") @RequestParam(required=false) Boolean superadmin,
            @Parameter(description = "Include records created at or after this ISO 8601 timestamp.") @RequestParam(required=false) java.time.OffsetDateTime createdFrom,
            @Parameter(description = "Include records created at or before this ISO 8601 timestamp.") @RequestParam(required=false) java.time.OffsetDateTime createdTo,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page,
            @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="id,asc") String sort) {
        return service.users(q, name, email, verified, superadmin, createdFrom, createdTo, page, size, sort);
    }

    // Organization listing: active membership for regular users, global for superadmin.
    @GetMapping
    @Operation(summary = "Search organizations", description = "Search, filter, sort and paginate organizations. Superadmins see all organizations; regular users see organizations where they have an active membership.")
    public Map<String, Object> listOrganizations(
            @Parameter(description = "Keyword search across the supported text columns.") @RequestParam(required=false) String q,
            @Parameter(description = "Filter by name (case-insensitive partial match).") @RequestParam(required=false) String name,
            @Parameter(description = "Filter by email address (case-insensitive partial match).") @RequestParam(required=false) String email,
            @Parameter(description = "Filter by phone number.") @RequestParam(required=false) String phone,
            @Parameter(description = "Filter by time-zone identifier.") @RequestParam(required=false) String timezone,
            @Parameter(description = "Filter by status.") @RequestParam(required=false) String status,
            @Parameter(description = "Filter by whether booking is enabled.") @RequestParam(required=false) Boolean bookingEnabled,
            @Parameter(description = "Include records created at or after this ISO 8601 timestamp.") @RequestParam(required=false) java.time.OffsetDateTime createdFrom,
            @Parameter(description = "Include records created at or before this ISO 8601 timestamp.") @RequestParam(required=false) java.time.OffsetDateTime createdTo,
            @Parameter(description = "Zero-based page index.") @RequestParam(defaultValue="0") int page,
            @Parameter(description = "Page size; the server caps the maximum number of records.") @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Sort by one supported field and direction, for example name,asc or id,desc.") @RequestParam(defaultValue="id,asc") String sort,
            Authentication authentication) {
        return service.listOrganizations(q, name, email, phone, timezone, status, bookingEnabled,
                createdFrom, createdTo, page, size, sort, authentication);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an organization", description = "Create an organization and make the authenticated user its owner.")
    public Map<String, Object> createOrganization(
            @Valid @RequestBody BackofficeDto.OrganizationCreate request,
            Authentication authentication) {
        return service.createOrganization(request, authentication);
    }
}
