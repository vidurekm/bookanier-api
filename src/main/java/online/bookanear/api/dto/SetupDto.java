package online.bookanear.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class SetupDto {
    private SetupDto() {}
    public record LocationInput(@NotBlank String name, String address, String timezone, Double latitude, Double longitude) {
        public LocationInput(String name, String address, String timezone) {
            this(name, address, timezone, null, null);
        }
    }
    // Null for previously saved drafts means bookable (backwards compatibility).
    public record MemberInput(@NotBlank String name, List<@Min(0) Integer> locationIndexes, Boolean providesServices, Boolean canManageBookings, Boolean canApproveOwnAbsence) {
        public MemberInput(String name, List<Integer> locationIndexes, Boolean providesServices) {
            this(name, locationIndexes, providesServices, null, null);
        }
    }
    public record ServiceInput(@NotBlank String name, @Min(5) @Max(1440) int durationMinutes,
                               @NotNull @DecimalMin("0") BigDecimal price, @NotBlank String currency) {}
    public record OpeningIntervalInput(@Min(0) int locationIndex, @Min(1) @Max(7) int weekday,
                                       String start, String end) {}
    public record Options(boolean customerSelectResource, boolean showStaffChoice,
                          @Min(1) @Max(10000) int eventCapacity, String defaultPublicLanguage, String defaultCurrency, String dateFormat) {
        public Options(boolean customerSelectResource, boolean showStaffChoice, int eventCapacity, String defaultPublicLanguage, String defaultCurrency) {
            this(customerSelectResource, showStaffChoice, eventCapacity, defaultPublicLanguage, defaultCurrency, null);
        }
        public Options(boolean customerSelectResource, boolean showStaffChoice, int eventCapacity) {
            this(customerSelectResource, showStaffChoice, eventCapacity, null, null, null);
        }
    }
    public record Draft(
        @NotBlank String templateKey, boolean multiLocation,
        List<@NotNull LocationInput> locations,
        List<@NotNull MemberInput> members,
        List<@NotNull ServiceInput> services,
        boolean trackResources, @Min(0) @Max(500) int resourceCount,
        @Min(1) @Max(1000) int resourceCapacity, String resourceName,
        boolean customerSelectResource, boolean showStaffChoice,
        boolean useWaitlist, @Min(1) @Max(10000) int eventCapacity,
        String workingStart, String workingEnd,
        boolean ownerProvidesServices, @Min(0) int ownerLocationIndex,
        List<@NotNull OpeningIntervalInput> openingHours,
        String defaultPublicLanguage, String defaultCurrency, String dateFormat
    ) {
        public Draft(String templateKey, boolean multiLocation, List<LocationInput> locations,
                List<MemberInput> members, List<ServiceInput> services, boolean trackResources,
                int resourceCount, int resourceCapacity, String resourceName,
                boolean customerSelectResource, boolean showStaffChoice, boolean useWaitlist,
                int eventCapacity, String workingStart, String workingEnd,
                boolean ownerProvidesServices, int ownerLocationIndex, List<OpeningIntervalInput> openingHours,
                String defaultPublicLanguage, String defaultCurrency) {
            this(templateKey,multiLocation,locations,members,services,trackResources,resourceCount,
                    resourceCapacity,resourceName,customerSelectResource,showStaffChoice,useWaitlist,
                    eventCapacity,workingStart,workingEnd,ownerProvidesServices,ownerLocationIndex,openingHours,
                    defaultPublicLanguage,defaultCurrency,null);
        }
        // Preserve source compatibility with the previous draft constructor.
        public Draft(String templateKey, boolean multiLocation, List<LocationInput> locations,
                List<MemberInput> members, List<ServiceInput> services, boolean trackResources,
                int resourceCount, int resourceCapacity, String resourceName,
                boolean customerSelectResource, boolean showStaffChoice, boolean useWaitlist,
                int eventCapacity, String workingStart, String workingEnd,
                boolean ownerProvidesServices, int ownerLocationIndex, List<OpeningIntervalInput> openingHours) {
            this(templateKey,multiLocation,locations,members,services,trackResources,resourceCount,
                    resourceCapacity,resourceName,customerSelectResource,showStaffChoice,useWaitlist,
                    eventCapacity,workingStart,workingEnd,ownerProvidesServices,ownerLocationIndex,openingHours,
                    null,null,null);
        }
    }
}
