package online.bookanear.api.dto;

import jakarta.validation.constraints.NotNull;

public final class MediaDto {
    private MediaDto() {}

    public record AttachImage(@NotNull Long mediaId, int sortOrder, boolean primary) {}
    public record ImageInfo(long id, String url, String contentType, int width, int height,
                            int sortOrder, boolean primary) {}
}
