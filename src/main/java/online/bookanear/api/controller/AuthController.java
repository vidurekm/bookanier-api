package online.bookanear.api.controller;

import online.bookanear.api.service.AuthService;
import online.bookanear.api.dto.AuthDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "01 - Authentication", description = "Email/password login and bearer-token issuance.")
public class AuthController {

    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Authenticate an existing user with an email address and password. Returns an HS256 bearer JWT for a verified account.")
    @ApiResponse(responseCode = "200", description = "JWT access token issued",
            content = @Content(schema = @Schema(implementation = AuthDto.LoginResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @ApiResponse(responseCode = "403", description = "Email address not verified")
    public AuthDto.LoginResponse login(@Valid @RequestBody AuthDto.LoginRequest request) {
        return service.login(request);
    }
}
