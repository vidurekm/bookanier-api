package online.bookanear.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class BackofficeDto {
    private BackofficeDto() {
    }

    @Schema(description = "Organization details for creation.")
    public record CreateOrganization(@Schema(description = "Organization display name.", example = "Example Studio") @NotBlank String name, @Schema(description = "URL-friendly organization identifier.", example = "example-studio") @NotBlank String slug, @Schema(description = "Organization contact email.", example = "hello@example.com") @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email) {
    }

    @Schema(description = "New organization location.")
    public record CreateLocation(@Schema(description = "Location display name.", example = "Main branch") @NotBlank String name, @Schema(description = "IANA time-zone identifier.", example = "Europe/Prague") @NotBlank String timezone, @Schema(description = "Location address.", example = "Prague, Czechia") String address, Double latitude, Double longitude) {
        public CreateLocation(String name, String timezone, String address) {
            this(name, timezone, address, null, null);
        }
    }

    @Schema(description = "New service catalog entry.")
    public record CreateService(@Schema(description = "Service display name.", example = "Haircut") @NotBlank String name, @Schema(description = "Optional description of the service.", example = "Standard haircut") String description) {
    }

    @Schema(description = "Existing user to be added as an organization member.")
    public record CreateMember(@Schema(description = "Member display name.", example = "Alex Smith") @NotBlank String displayName, @Schema(description = "Existing user account identifier.", example = "12") @NotNull Long userId) {
    }

    @Schema(description = "New customer contact details.")
    public record CreateCustomer(@Schema(description = "Customer display name.", example = "Alex Smith") @NotBlank String name, @Schema(description = "Customer contact email.", example = "alex@example.com") @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email, @Schema(description = "Customer contact phone number.", example = "+420777123456") String phone, @Schema(description = "Internal customer notes.", example = "Prefers afternoon appointments") String notes) {
        public CreateCustomer(String name, String email, String phone) {
            this(name, email, phone, null);
        }
    }

    @Schema(description = "Editable organization profile and booking configuration.")
    public record OrganizationUpdate(
            @Schema(description = "Organization display name.", example = "Example Studio") @NotBlank String name,
            @Schema(description = "Organization contact email.", example = "hello@example.com") @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email,
            @Schema(description = "Organization contact phone.", example = "+420777123456") String phone,
            @Schema(description = "Default IANA time-zone identifier.", example = "Europe/Prague") @NotBlank String defaultTimezone,
            @Schema(description = "Whether customers can make bookings.", example = "true") boolean bookingEnabled
    ) {
    }

    @Schema(description = "Availability exception for a member or location.")
    public record AbsenceCreate(@Schema(description = "Optional location identifier.", example = "2") Long locationId, @Schema(description = "Optional member identifier.", example = "3") Long memberId, @Schema(description = "Override start timestamp with UTC offset.", example = "2026-11-02T12:00:00+01:00") @NotNull java.time.OffsetDateTime startsAt,
                                @Schema(description = "Override end timestamp with UTC offset.", example = "2026-11-02T13:00:00+01:00") @NotNull java.time.OffsetDateTime endsAt, @Schema(description = "Reason for the availability exception.", example = "Lunch break") String reason, @Schema(description = "Whether this interval adds or removes availability.", example = "unavailable") @Pattern(regexp = "available|unavailable") String type) {
    }

    @Schema(description = "Recurring member schedule rule effective for a date range.")
    public record ScheduleCreate(@Schema(description = "Location identifier.", example = "2") @NotNull Long locationId, @Schema(description = "Member identifier.", example = "3") @NotNull Long memberId, @Schema(description = "ISO weekday number (1=Monday, 7=Sunday).", example = "1") @Min(1) @Max(7) int weekday,
                                 @Schema(description = "Local start time.", example = "08:00") @NotNull java.time.LocalTime startTime, @Schema(description = "Local end time.", example = "16:00") @NotNull java.time.LocalTime endTime,
                                 @Schema(description = "First calendar date the rule applies.", example = "2026-11-01") @NotNull LocalDate effectiveFrom, @Schema(description = "Optional last calendar date the rule applies.", example = "2026-12-31") LocalDate effectiveTo) {
    }

    @Schema(description = "Local time interval in a single calendar day.")
    public record TimeInterval(@Schema(description = "Local interval start time.", example = "08:00") @NotNull java.time.LocalTime from, @Schema(description = "Local interval end time; must be after from.", example = "12:00") @NotNull java.time.LocalTime to) {
    }

    @Schema(description = "Opening or working intervals for one weekday.")
    public record ScheduleDay(@Schema(description = "ISO weekday number (1=Monday, 7=Sunday).", example = "1") @Min(1) @Max(7) int weekday, @Schema(description = "Non-overlapping time intervals; an empty list means closed/off duty.") @NotNull java.util.List<@Valid TimeInterval> intervals) {
    }

    @Schema(description = "Weekly opening or working hours; omitted days are closed or off duty.")
    public record WeeklySchedule(@Schema(description = "Weekly schedule days. Omitted days are closed/off duty.") @NotNull java.util.List<@Valid ScheduleDay> days) {
    }

    @Schema(description = "Override of opening or working hours for a specific calendar date.")
    public record DatedScheduleDay(@Schema(description = "Specific calendar date to override.", example = "2026-11-02") @NotNull LocalDate date,
                                   @Schema(description = "Intervals for this date. Empty means closed/off duty.") @NotNull java.util.List<@Valid TimeInterval> intervals) {
    }

    @Schema(description = "Batch of date-specific schedule overrides.")
    public record DatedSchedule(@Schema(description = "Date-specific schedule overrides. Omitted dates retain their weekly schedule.") @NotNull java.util.List<@Valid DatedScheduleDay> days) {
    }

    @Schema(description = "Active/inactive assignment between a member and a service.")
    public record Assignment(@Schema(description = "Whether the service is assigned to the member.", example = "true") boolean active) {
    }

    @Schema(description = "Editable location details.")
    public record LocationUpsert(@Schema(description = "Location display name.", example = "Main branch") @NotBlank String name, @Schema(description = "Location address.", example = "Prague, Czechia") String address, @Schema(description = "IANA time-zone identifier.", example = "Europe/Prague") @NotBlank String timezone, @Schema(description = "Whether the location is active.", example = "true") boolean active, Double latitude, Double longitude) {
        public LocationUpsert(String name, String address, String timezone, boolean active) {
            this(name, address, timezone, active, null, null);
        }
    }

    @Schema(description = "Editable customer contact details and internal notes.")
    public record CustomerUpsert(@Schema(description = "Customer display name.", example = "Alex Smith") @NotBlank String name, @Schema(description = "Customer contact email.", example = "alex@example.com") @Email(regexp = EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY) String email, @Schema(description = "Customer contact phone.", example = "+420777123456") String phone, @Schema(description = "Internal customer notes.", example = "Prefers afternoon appointments") String notes) {
    }

    @Schema(description = "Editable organization member role and status.")
    public record MemberUpsert(@Schema(description = "Member display name.", example = "Alex Smith") @NotBlank String displayName, @Schema(description = "Organization role: owner, admin or worker.", example = "worker") @Pattern(regexp = "owner|admin|worker") String role, @Schema(description = "Whether the member is active.", example = "true") boolean active,
        Boolean canManageBookings, Boolean canApproveOwnAbsence) {
        public MemberUpsert(String displayName, String role, boolean active) {
            this(displayName,role,active,null,null);
        }
    }

    @Schema(description = "Effective-dated service pricing, duration and slot settings.")
    public record ServiceVersionCreate(@Schema(description = "Start date of the service version.", example = "2026-11-01") @NotNull LocalDate validFrom, @Schema(description = "Optional end date of the service version.", example = "2026-12-31") LocalDate validTo,
                                       @Schema(description = "Service duration in minutes.", example = "45") @NotNull @Min(1) Integer durationMinutes, @Schema(description = "Available slot spacing in minutes.", example = "15") @NotNull @Min(1) Integer slotIntervalMinutes,
                                       @Schema(description = "Preparation buffer before the service in minutes.", example = "5") @Min(0) Integer bufferBeforeMinutes, @Schema(description = "Cleanup buffer after the service in minutes.", example = "5") @Min(0) Integer bufferAfterMinutes,
                                       @Schema(description = "Service price.", example = "450.00") @NotNull @DecimalMin("0.00") BigDecimal price, @Schema(description = "ISO 4217 currency code.", example = "CZK") @NotBlank String currency,
                                       @Pattern(regexp = "FIXED|CUSTOM") String durationMode,
                                       @Min(1) Integer maxDurationMinutes) {
    }

    @Schema(description = "Editable service catalog details.")
    public record ServiceUpsert(@Schema(description = "Service display name.", example = "Haircut") @NotBlank String name, @Schema(description = "Optional service description.", example = "Standard haircut") String description, @Schema(description = "Whether the service is active.", example = "true") boolean active) {
    }
    public record ResourceTypeCreate(@NotBlank String name,
            @NotNull @Pattern(regexp = "staff|space|equipment|other") String category) {}

    public record ResourceTypeUpdate(@NotBlank String name,
            @NotNull @Pattern(regexp = "staff|space|equipment|other") String category,
            boolean active) {}

    public record ResourceCreate(@NotNull Long locationId, @NotNull Long resourceTypeId,
            @NotBlank String name, @Min(1) int capacity, Long memberId, String inventoryCode) {}

    public record ResourceUpdate(@NotBlank String name, @Min(1) int capacity,
            boolean active, @NotNull @Pattern(regexp = "available|maintenance|retired") String resourceState) {}

    public record ResourceRequirement(@NotNull Long resourceTypeId,
            @NotNull @Min(1) Integer quantity, @Min(1) Integer minCapacity) {}

    public record ResourceRequirements(@NotNull java.util.List<@Valid ResourceRequirement> requirements) {}

}
