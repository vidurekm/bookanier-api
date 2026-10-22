package online.bookanear.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public final class AuthDto {
    private AuthDto() {
    }

    @Schema(description = "Credentials used to obtain a bearer JWT.")
    public record LoginRequest(
            @Schema(description = "Email address of the user.", example = "user@example.com") @NotBlank @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email,
            @Schema(description = "Account password.", example = "a-strong-password", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank String password
    ) {
    }

    @Schema(description = "JWT token and expiration metadata returned after successful login.")
    public record LoginResponse(@Schema(description = "Signed JWT bearer access token.") String accessToken, @Schema(description = "Authorization scheme, always Bearer.", example = "Bearer") String tokenType, @Schema(description = "Token lifetime in seconds.", example = "604800") long expiresIn) {
    }

    @Schema(description = "Identity of the authenticated account.")
    public record CurrentUser(@Schema(description = "User's display name.") String name,
                              @Schema(description = "User's email address.") String email,
                              @Schema(description = "Email address awaiting confirmation, if any.") String pendingEmail) {}
}
