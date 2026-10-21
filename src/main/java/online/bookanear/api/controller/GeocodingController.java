package online.bookanear.api.controller;

import online.bookanear.api.service.GeocodingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/geocoding")
public class GeocodingController {
    private final GeocodingService geocoding;
    public GeocodingController(GeocodingService geocoding) { this.geocoding=geocoding; }

    @GetMapping("/address")
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public List<GeocodingService.Result> search(@PathVariable long org, @RequestParam String address) {
        return geocoding.search(address);
    }
}
