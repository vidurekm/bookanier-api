package online.bookanear.api.controller;

import jakarta.validation.Valid;
import online.bookanear.api.dto.PasswordResetDto;
import online.bookanear.api.service.PasswordResetService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/password")
public class PasswordResetController {
    private final PasswordResetService service;
    public PasswordResetController(PasswordResetService service) { this.service = service; }

    @PostMapping("/forgot")
    public ResponseEntity<PasswordResetDto.Accepted> forgot(@Valid @RequestBody PasswordResetDto.Request request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.request(request));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String,String>> reset(@Valid @RequestBody PasswordResetDto.Confirm request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.confirm(request));
    }
}
