package online.bookanear.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only diagnostic metadata. Never send passwords, tokens, request bodies or cookies. */
public record FrontendErrorDto(
        @NotBlank @Size(max = 60) String type,
        @NotBlank @Size(max = 300) String path,
        @NotBlank @Size(max = 500) String message,
        @Min(0) @Max(599) Integer status,
        @Size(max = 100) String requestId) {
}
