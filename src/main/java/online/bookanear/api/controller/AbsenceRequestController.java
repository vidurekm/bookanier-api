package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.service.AbsenceRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/absence-requests")
public class AbsenceRequestController {
    private final AbsenceRequestService service;
    public AbsenceRequestController(AbsenceRequestService service) { this.service=service; }

    @GetMapping @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object list(@PathVariable long org, Authentication auth) { return service.list(org,auth); }

    @GetMapping("/locations") @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object locations(@PathVariable long org, Authentication auth) { return service.locations(org,auth); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object request(@PathVariable long org,@Valid @RequestBody AbsenceRequestService.CreateRequest data,
                          Authentication auth) {return service.create(org,data,auth);}

    @PostMapping("/{id}/approve") @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object approve(@PathVariable long org,@PathVariable long id,Authentication auth) {
        return service.decide(org,id,true,auth);
    }

    @PostMapping("/{id}/reject") @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object reject(@PathVariable long org,@PathVariable long id,Authentication auth) {
        return service.decide(org,id,false,auth);
    }
}
