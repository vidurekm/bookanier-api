package online.bookanear.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class InvitationDto {
    private InvitationDto() {}

    public record CreateRequest(
            @NotBlank @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email,
            @NotBlank @Size(max = 200) String displayName,
            @Pattern(regexp = "owner|admin|worker") String role,
            Boolean canManageBookings,
            Boolean canManageOwnBookings,
            Boolean canApproveOwnAbsence,
            @jakarta.validation.constraints.Positive Long memberId) {
        public CreateRequest(String email, String displayName, String role, Boolean canManageBookings,
                             Boolean canApproveOwnAbsence, Long memberId) {
            this(email, displayName, role, canManageBookings, null, canApproveOwnAbsence, memberId);
        }
    }

    public record AcceptRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,128}") String token,
            @Size(min = 8, max = 128) String password) {}

    public record InvitationInfo(long organizationId, String email, String displayName,
                                String organizationName, String role, boolean accountExists) {}
}
