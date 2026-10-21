package online.bookanear.api.exception;

/** Stable client-facing identifiers. Clients must never display the diagnostic English message. */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static String forApiException(ApiException e) {
        if (e.code != null) return e.code;
        String msg = e.getMessage();
        if (msg == null) return forStatus(e.status.value());
        return switch (msg) {
            case "Slot occupied", "Worker or location unavailable", "Location unavailable", "Location is closed" -> "SLOT_UNAVAILABLE";
            case "Invalid link", "Link expired or invalid", "View-only link" -> "INVALID_BOOKING_LINK";
            case "endsAt is required for CUSTOM duration", "endsAt must match fixed service duration",
                 "Invalid reservation duration", "Fixed duration mismatch" -> "INVALID_BOOKING_DURATION";
            case "Only valid JPEG and PNG images are supported", "Unsupported image target", "Empty image" -> "UNSUPPORTED_IMAGE";
            case "Image is empty or too large", "Image is too large", "Processed image is too large",
                 "Image dimensions exceed limit" -> "FILE_TOO_LARGE";
            case "Image not found", "Image assignment not found", "Image target not found" -> "RESOURCE_NOT_FOUND";
            case "Invalid or expired verification token" -> "VALIDATION_ERROR";
            default -> {
                if (msg.startsWith("Not enough available resources of type ")) yield "INSUFFICIENT_RESOURCES";
                if (msg.contains("overlap") || msg.contains("Overlap") || msg.contains("shift conflict")) yield "SCHEDULE_CONFLICT";
                yield forStatus(e.status.value());
            }
        };
    }

    public static String forStatus(int status) {
        return switch (status) {
            case 400, 422 -> "VALIDATION_ERROR";
            case 401 -> "AUTH_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "FILE_TOO_LARGE";
            case 429 -> "RATE_LIMITED";
            case 502, 503, 504 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "SERVER_ERROR" : "UNKNOWN_ERROR";
        };
    }
}
