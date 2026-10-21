package online.bookanear.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public final class PublicBookingDto {
    private PublicBookingDto() {
    }

    @Schema(description = "Publicly visible organization information.")
    public record PublicOrganization(
            @Schema(description = "Public UUID used in URLs.") UUID publicId,
            @Schema(description = "Organization display name.", example = "Example Studio") String name
    ) {
    }
}
