package online.bookanear.api.controller;

import online.bookanear.api.dto.MediaDto;
import online.bookanear.api.service.MediaService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public")
@ConditionalOnProperty(prefix = "app.media", name = "enabled", havingValue = "true")
public class MediaPublicController {
    private final MediaService service;
    public MediaPublicController(MediaService service) { this.service = service; }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<byte[]> image(@PathVariable long mediaId) {
        var image = service.read(mediaId, null);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }

    @GetMapping("/organizations/{org}/images/{kind}/{targetId}")
    public List<MediaDto.ImageInfo> list(@PathVariable long org, @PathVariable String kind,
                                         @PathVariable long targetId) {
        return service.list(org, MediaService.Kind.parse(kind), targetId, true);
    }
}
