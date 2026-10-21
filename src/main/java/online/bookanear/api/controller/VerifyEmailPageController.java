package online.bookanear.api.controller;

import online.bookanear.api.service.FrontendLinks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.regex.Pattern;

/** Backwards-compatible redirect for emails issued before the frontend move. No HTML in backend. */
@RestController
public class VerifyEmailPageController {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private final String frontendUrl;
    public VerifyEmailPageController(@Value("${registration.home-url:http://localhost:5173/}") String frontendUrl) {
        this.frontendUrl = FrontendLinks.base(frontendUrl);
    }
    @GetMapping("/verify-email")
    public ResponseEntity<Void> redirect(@RequestParam(required=false) String token) {
        var builder = UriComponentsBuilder.fromUriString(FrontendLinks.localized(frontendUrl,"/verify-email"));
        if (token != null && TOKEN.matcher(token).matches()) builder.queryParam("token", token);
        var headers = new HttpHeaders();
        headers.setLocation(builder.build().encode().toUri());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("Referrer-Policy", "no-referrer");
        return new ResponseEntity<>(headers, HttpStatus.SEE_OTHER);
    }
}
