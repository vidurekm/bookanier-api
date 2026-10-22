package online.bookanear.api.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import online.bookanear.api.dto.InvitationDto;
import online.bookanear.api.service.InvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "03 - Organization invitations", description = "Email invitations for organization members.")
public class InvitationController {
    private final InvitationService service;

    public InvitationController(InvitationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/invitations")
    public InvitationDto.InvitationInfo info(@RequestParam String token) {
        return service.info(token);
    }

    @PostMapping("/api/v1/invitations/accept")
    public Map<String, String> accept(@Valid @RequestBody InvitationDto.AcceptRequest request,
                                      Authentication authentication) {
        return service.accept(request, authentication);
    }

    @GetMapping("/api/v1/backoffice/organizations/{org}/invitations")
    @org.springframework.security.access.prepost.PreAuthorize("@organizationSecurity.isOwner(#p0, authentication)")
    public Object pending(@PathVariable long org) {
        return service.pending(org);
    }

    @PostMapping("/api/v1/backoffice/organizations/{org}/invitations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @org.springframework.security.access.prepost.PreAuthorize("@organizationSecurity.isOwner(#p0, authentication)")
    public Object create(@PathVariable long org, @Valid @RequestBody InvitationDto.CreateRequest request) {
        return service.create(org, request);
    }
}
