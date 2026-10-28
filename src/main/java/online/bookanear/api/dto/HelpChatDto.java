package online.bookanear.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class HelpChatDto {
    private HelpChatDto() {}

    public record Message(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 1500) String content) {}

    public record Request(@NotNull @Size(max = 12) List<@Valid Message> messages) {}

    public record Reply(String content) {}
    public record Status(boolean enabled) {}
}
