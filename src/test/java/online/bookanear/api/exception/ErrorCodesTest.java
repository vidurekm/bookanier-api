package online.bookanear.api.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodesTest {
    @Test
    void explicitCodeIsPreserved() {
        var exception = new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "Slot occupied");
        assertEquals("SLOT_UNAVAILABLE", ErrorCodes.forApiException(exception));
    }

    @Test
    void legacyErrorsHaveStableCode() {
        assertEquals("SLOT_UNAVAILABLE", ErrorCodes.forApiException(new ApiException(HttpStatus.CONFLICT, "Slot occupied")));
        assertEquals("INSUFFICIENT_RESOURCES", ErrorCodes.forApiException(new ApiException(HttpStatus.CONFLICT, "Not enough available resources of type 5")));
        assertEquals("VALIDATION_ERROR", ErrorCodes.forApiException(new ApiException(HttpStatus.BAD_REQUEST, "Unknown field")));
        assertEquals("NOT_FOUND", ErrorCodes.forStatus(404));
    }
}
