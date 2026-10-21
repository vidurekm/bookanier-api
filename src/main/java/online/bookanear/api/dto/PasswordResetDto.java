package online.bookanear.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Deliberately use the same response for existing and unknown email addresses. */
public final class PasswordResetDto {
    private PasswordResetDto() {}
    public record Request(@NotBlank @Email String email) {}
    public record Confirm(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,128}") String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record Accepted(String message) {}
}
