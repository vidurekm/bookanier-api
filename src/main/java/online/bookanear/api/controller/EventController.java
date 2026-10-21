package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.EventDto;
import online.bookanear.api.service.EventService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class EventController {
    private final EventService service;
    public EventController(EventService service) { this.service=service; }
    @GetMapping("/api/v1/backoffice/organizations/{org}/event-series")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object series(@PathVariable long org) { return service.series(org); }
    @PostMapping("/api/v1/backoffice/organizations/{org}/event-series")
    @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object createSeries(@PathVariable long org,@Valid @RequestBody EventDto.Series r) { return service.createSeries(org,r); }
    @PutMapping("/api/v1/backoffice/organizations/{org}/event-series/{series}")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object updateSeries(@PathVariable long org,@PathVariable long series,@Valid @RequestBody EventDto.Series r) { return service.updateSeries(org,series,r); }
    @PostMapping("/api/v1/backoffice/organizations/{org}/event-series/{series}/occurrences")
    @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object createOccurrence(@PathVariable long org,@PathVariable long series,@Valid @RequestBody EventDto.Occurrence r) { return service.createOccurrence(org,series,r); }
    @GetMapping("/api/v1/backoffice/organizations/{org}/events")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object occurrences(@PathVariable long org) { return service.occurrences(org); }
    @GetMapping("/api/v1/backoffice/organizations/{org}/events/{id}/registrations")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object registrations(@PathVariable long org,@PathVariable long id) { return service.registrations(org,id); }
    @PostMapping("/api/v1/backoffice/organizations/{org}/events/{id}/cancel")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public Object cancelOccurrence(@PathVariable long org,@PathVariable long id) { return service.cancelOccurrence(org,id); }
    @GetMapping("/api/v1/public/organizations/{publicId}/events")
    public Object publicEvents(@PathVariable UUID publicId) { return service.publicEvents(publicId); }
    @PostMapping("/api/v1/public/organizations/{publicId}/events/{id}/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    public Object register(@PathVariable UUID publicId,@PathVariable long id,@Valid @RequestBody EventDto.Registration r) { return service.register(publicId,id,r); }
    @GetMapping("/api/v1/public/events/manage/{token}")
    public Object registration(@PathVariable String token) { return service.publicRegistration(token); }
    @PostMapping("/api/v1/public/events/manage/{token}/cancel")
    public Object cancelRegistration(@PathVariable String token) { return service.cancelRegistration(token); }
}
