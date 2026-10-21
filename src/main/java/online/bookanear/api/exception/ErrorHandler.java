package online.bookanear.api.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(ScheduleConflictException.class)
    ResponseEntity<?> scheduleConflict(ScheduleConflictException e) {
        return ResponseEntity.status(409).body(Map.of("code", "SCHEDULE_CONFLICT", "error", e.getMessage(),
                "conflictCount", e.getConflicts().stream().map(r -> r.get("reservationId")).distinct().count(),
                "conflicts", e.getConflicts()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("code", ErrorCodes.forApiException(e), "error", e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<?> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "error", "Invalid request"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("code", "CONFLICT", "error", "Conflicting or invalid booking data"));
    }
}
