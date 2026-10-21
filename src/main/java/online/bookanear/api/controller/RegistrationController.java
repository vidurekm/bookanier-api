package online.bookanear.api.controller;

import online.bookanear.api.service.RegistrationService;
import online.bookanear.api.dto.RegistrationDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/registration")
@Tag(name = "02 - Registration", description = "Organization registration and email verification.")
public class RegistrationController {
    private final RegistrationService service;
    public RegistrationController(RegistrationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register an organization", description = "Create a pending organization and its owner account, then send an email containing a one-time verification link.")
    @ApiResponse(responseCode = "202", description = "Registration accepted; verification email sent",
            content = @Content(schema = @Schema(implementation = RegistrationDto.RegisterResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "409", description = "Email address is already registered")
    public Map<String, String> register(@Valid @RequestBody RegistrationDto.RegisterRequest req) {
        return service.register(req);
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify registration", description = "Consume a valid, unexpired email verification token, verify the owner account, and activate the organization.")
    @ApiResponse(responseCode = "200", description = "Organization and owner account verified",
            content = @Content(schema = @Schema(implementation = RegistrationDto.VerifyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid, expired or already used token")
    public Map<String, String> verify(@Valid @RequestBody RegistrationDto.VerifyRequest req) {
        return service.verify(req);
    }
}
