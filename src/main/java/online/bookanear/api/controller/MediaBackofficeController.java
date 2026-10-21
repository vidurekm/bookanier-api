package online.bookanear.api.controller;

import online.bookanear.api.dto.MediaDto;
import online.bookanear.api.service.MediaService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}")
@SecurityRequirement(name = "bearerAuth")
@ConditionalOnProperty(prefix = "app.media", name = "enabled", havingValue = "true")
public class MediaBackofficeController {
    private final MediaService service;
    public MediaBackofficeController(MediaService service) { this.service = service; }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public MediaDto.ImageInfo upload(@PathVariable long org, @RequestParam("file") MultipartFile file) {
        return service.upload(org, file);
    }

    @GetMapping("/media/{mediaId}")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public ResponseEntity<byte[]> download(@PathVariable long org, @PathVariable long mediaId) {
        var image = service.read(mediaId, org);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }

    @DeleteMapping("/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public void delete(@PathVariable long org, @PathVariable long mediaId) {
        service.delete(org, mediaId);
    }

    @PostMapping("/images/{kind}/{targetId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public MediaDto.ImageInfo attach(@PathVariable long org, @PathVariable String kind,
                                      @PathVariable long targetId, @Valid @RequestBody MediaDto.AttachImage request) {
        return service.attach(org, MediaService.Kind.parse(kind), targetId, request);
    }

    @GetMapping("/images/{kind}/{targetId}")
    @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public List<MediaDto.ImageInfo> list(@PathVariable long org, @PathVariable String kind,
                                          @PathVariable long targetId) {
        return service.list(org, MediaService.Kind.parse(kind), targetId, false);
    }

    @DeleteMapping("/images/{kind}/{targetId}/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@organizationSecurity.canWrite(#p0, authentication)")
    public void detach(@PathVariable long org, @PathVariable String kind,
                       @PathVariable long targetId, @PathVariable long mediaId) {
        service.detach(org, MediaService.Kind.parse(kind), targetId, mediaId);
    }
}
