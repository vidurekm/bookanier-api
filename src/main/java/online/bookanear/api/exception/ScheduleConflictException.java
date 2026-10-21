package online.bookanear.api.exception;

import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Map;

public final class ScheduleConflictException extends ApiException {
    private final List<Map<String, Object>> conflicts;

    public ScheduleConflictException(List<Map<String, Object>> conflicts, String message) {
        super(HttpStatus.CONFLICT, message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Map<String, Object>> getConflicts() { return conflicts; }
}
