package online.bookanear.api.controller;

import online.bookanear.api.service.PublicCatalogService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public, read-only booking catalog. No personal customer data is exposed. */
@RestController
@RequestMapping("/api/v1/public/organizations/{publicId}")
public class PublicCatalogController {
    private final PublicCatalogService catalog;
    public PublicCatalogController(PublicCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@PathVariable UUID publicId,
                                       @RequestParam(required = false) LocalDate date) {
        return catalog.catalog(publicId, date == null ? LocalDate.now() : date);
    }

    @GetMapping("/slots")
    public Map<String, Object> slots(@PathVariable UUID publicId,
                                     @RequestParam long locationId,
                                     @RequestParam long serviceId,
                                     @RequestParam LocalDate date,
                                     @RequestParam(required = false) Integer partySize,
                                     @RequestParam(required = false) Integer quantity,
                                     @RequestParam(required = false) List<Long> preferredMemberIds,
                                     @RequestParam(required = false) Long preferredResourceId) {
        return catalog.slots(publicId, locationId, serviceId, date, partySize, quantity,
                preferredMemberIds, preferredResourceId);
    }
}
