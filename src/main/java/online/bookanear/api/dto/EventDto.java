package online.bookanear.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class EventDto {
    private EventDto() {}
    public record Series(@NotNull Long locationId, @NotBlank String title, String description,
                         @Min(1) @Max(10000) int capacity, @NotNull @DecimalMin("0") BigDecimal price,
                         @Pattern(regexp="[A-Z]{3}") String currency) {}
    public record Occurrence(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                             @Min(1) @Max(10000) Integer capacityOverride) {}
    public record Registration(@NotBlank String customerName, @NotBlank @Email String customerEmail,
                               String customerPhone, @Min(1) @Max(100) int seats) {}
}
