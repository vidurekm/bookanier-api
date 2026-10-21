package online.bookanear.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class RegistrationDto {
    private RegistrationDto() {
    }

    @Schema(description = "Details of a new organization and its first owner account.")
    public record RegisterRequest(
            @Schema(description = "Display name of the new organization.", example = "Example Studio") @NotBlank String organizationName,
            @Schema(description = "Full name of the organization owner.", example = "Alex Smith") @NotBlank String ownerName,
            @Schema(description = "Owner email address, used for login and verification.", example = "alex@example.com") @NotBlank @Email String email,
            @Schema(description = "New account password (12–128 characters).", example = "a-long-strong-password", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(min = 12, max = 128) String password
    ) {
    }

    @Schema(description = "One-time token from the email verification link.")
    public record VerifyRequest(@Schema(description = "One-time verification token from the registration email.", example = "example-verification-token", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank String token) {
    }
    @Schema(description = "Response confirming that registration was accepted.")
    public record RegisterResponse(
            @Schema(description = "Human-readable registration confirmation.", example = "Registration created; check your email") String message
    ) {}

    @Schema(description = "Response confirming successful email verification.")
    public record VerifyResponse(
            @Schema(description = "Verification status.", example = "verified") String status
    ) {}
}
