package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.SetupDto;
import online.bookanear.api.service.SetupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/setup")
public class SetupController {
    private final SetupService service;
    public SetupController(SetupService service) { this.service=service; }
    @GetMapping("/templates") @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object templates(@PathVariable long org) { return service.templates(); }
    @GetMapping("/owner") @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object owner(@PathVariable long org) { return service.owner(org); }
    @GetMapping @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object config(@PathVariable long org) { return service.config(org); }
    @PutMapping @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object save(@PathVariable long org,@Valid @RequestBody SetupDto.Draft draft) { return service.save(org,draft); }
    @PutMapping("/options") @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object options(@PathVariable long org,@Valid @RequestBody SetupDto.Options options) { return service.updateOptions(org,options); }
    @PostMapping("/complete") @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object complete(@PathVariable long org,@Valid @RequestBody SetupDto.Draft draft) { return service.finish(org,draft); }
}
