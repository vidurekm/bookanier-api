package online.bookanear.api.controller;

import online.bookanear.api.service.PublicBookingService;
import online.bookanear.api.dto.PublicBookingDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/organizations")
@Tag(name = "03 - Public Catalog", description = "Public organization information.")
public class PublicBookingController {

    private final PublicBookingService service;
    public PublicBookingController(PublicBookingService service) { this.service = service; }

    @GetMapping("/{publicId}")
    @Operation(summary = "Get public organization", description = "Return the public identifier and name of an organization that can be discovered without authentication.")
    public PublicBookingDto.PublicOrganization getOrganization(@Parameter(description = "Public UUID of the organization.") @PathVariable UUID publicId) {
        return service.getOrganization(publicId);
    }
}
