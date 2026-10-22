package online.bookanear.api.controller;

import online.bookanear.api.service.AuthService;
import online.bookanear.api.dto.AuthDto;
import online.bookanear.api.dto.AccountDto;

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
import org.springframework.security.core.Authentication;

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

    @org.springframework.web.bind.annotation.GetMapping("/me")
    @Operation(summary = "Get current user", description = "Return the name and email of the authenticated account.")
    @ApiResponse(responseCode = "200", description = "Authenticated account identity",
            content = @Content(schema = @Schema(implementation = AuthDto.CurrentUser.class)))
    public AuthDto.CurrentUser currentUser(Authentication authentication) {
        return service.currentUser(authentication);
    }

    @org.springframework.web.bind.annotation.PutMapping("/me")
    public AuthDto.CurrentUser updateName(Authentication authentication,
                                          @Valid @RequestBody AccountDto.UpdateName request) {
        return service.updateName(authentication, request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/me/email")
    public java.util.Map<String,String> changeEmail(Authentication authentication,
                                                    @Valid @RequestBody AccountDto.ChangeEmail request) {
        return service.requestEmailChange(authentication, request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/me/password")
    public java.util.Map<String,String> changePassword(Authentication authentication,
                                                       @Valid @RequestBody AccountDto.ChangePassword request) {
        return service.changePassword(authentication, request);
    }

    @PostMapping("/email/confirm")
    public java.util.Map<String,String> confirmEmailChange(@Valid @RequestBody AccountDto.ConfirmEmail request) {
        return service.confirmEmailChange(request);
    }
}
