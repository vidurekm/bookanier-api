package online.bookanear.api.controller;

import online.bookanear.api.service.CatalogAssignmentsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Tenant-scoped management of location/service and member/location assignments. */
@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}")
public class CatalogAssignmentsController {
    private final CatalogAssignmentsService service;
    public CatalogAssignmentsController(CatalogAssignmentsService service) { this.service = service; }
    public record Ids(@NotNull List<@NotNull Long> ids) {}

    @GetMapping("/locations/{loc}/services")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public List<Map<String,Object>> locationServices(@PathVariable long org,@PathVariable long loc) {
        return service.locationServices(org,loc);
    }
    @PutMapping("/locations/{loc}/services")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public List<Map<String,Object>> setLocationServices(@PathVariable long org,@PathVariable long loc,
                                                         @Valid @RequestBody Ids body) {
        return service.setLocationServices(org,loc,body.ids());
    }
    @GetMapping("/members/{member}/locations")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public List<Long> memberLocations(@PathVariable long org,@PathVariable long member) {
        return service.memberLocations(org,member);
    }
    @PutMapping("/members/{member}/locations")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public List<Long> setMemberLocations(@PathVariable long org,@PathVariable long member,
                                        @Valid @RequestBody Ids body) {
        return service.setMemberLocations(org,member,body.ids());
    }
}
