package online.bookanear.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.time.OffsetDateTime;
import java.util.List;

public final class BookingDto {
    private BookingDto() {
    }

    @Schema(description = "Details needed to create a customer reservation.")
    public record CreateBooking(@Schema(description = "Internal identifier of the organization.", example = "1") @NotNull Long organizationId, @Schema(description = "Location identifier.", example = "2") @NotNull Long locationId,
                                @Schema(description = "Service identifier.", example = "3") @NotNull Long serviceId,                                 @Schema(description = "Member who performs the service, required only when customers may choose staff.", example = "4") Long memberId, @Schema(description = "Requested start time with UTC offset (ISO 8601).", example = "2026-11-02T09:00:00+01:00") @NotNull OffsetDateTime startsAt,
                                @Schema(description = "Name of the booking customer.", example = "Alex Smith") @NotBlank String customerName, @Schema(description = "Customer contact email.", example = "alex@example.com") @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String customerEmail, @Schema(description = "Customer contact phone number.", example = "+420777123456") String customerPhone) {
    }

    /** Universal booking for a service with configured resource requirements. */
    @Schema(description = "Reservation of configured staff, space or equipment resources.")
    public record CreateUniversalBooking(
            @NotNull Long organizationId,
            @NotNull Long locationId,
            @NotNull Long serviceId,
            @NotNull OffsetDateTime startsAt,
            @Schema(description = "Required for CUSTOM-duration services (e.g. rental return time).")
            OffsetDateTime endsAt,
            @Min(1) Integer partySize,
            @Min(1) @Max(100) Integer quantity,
            Long preferredResourceId,
            Long preferredMemberId,
            List<Long> preferredMemberIds,
            @NotBlank String customerName,
            @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String customerEmail,
            String customerPhone,
            List<@jakarta.validation.constraints.Positive Long> excludedMemberIds) {
        public CreateUniversalBooking(Long organizationId, Long locationId, Long serviceId,
                OffsetDateTime startsAt, OffsetDateTime endsAt, Integer partySize, Integer quantity,
                Long preferredResourceId, Long preferredMemberId, String customerName,
                String customerEmail, String customerPhone) {
            this(organizationId, locationId, serviceId, startsAt, endsAt, partySize, quantity,
                    preferredResourceId, preferredMemberId, null, customerName, customerEmail, customerPhone, null);
        }
        public CreateUniversalBooking(Long organizationId, Long locationId, Long serviceId,
                OffsetDateTime startsAt, OffsetDateTime endsAt, Integer partySize, Integer quantity,
                Long preferredResourceId, Long preferredMemberId, List<Long> preferredMemberIds,
                String customerName, String customerEmail, String customerPhone) {
            this(organizationId, locationId, serviceId, startsAt, endsAt, partySize, quantity,
                    preferredResourceId, preferredMemberId, preferredMemberIds, customerName,
                    customerEmail, customerPhone, null);
        }
    }

    @Schema(description = "New start time requested for an existing reservation.")
    public record Reschedule(@Schema(description = "New start time with UTC offset (ISO 8601).", example = "2026-11-03T11:00:00+01:00") @NotNull OffsetDateTime startsAt) {
    }
}
