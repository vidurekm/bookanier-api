package online.bookanear.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AccountDto {
    private AccountDto() {}

    public record UpdateName(@NotBlank @Size(max = 200) String name) {}

    public record ChangeEmail(
            @NotBlank String currentPassword,
            @NotBlank @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) @Size(max = 320) String email,
            String language) {}

    public record ChangePassword(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {}

    public record ConfirmEmail(@NotBlank String token) {}
}
