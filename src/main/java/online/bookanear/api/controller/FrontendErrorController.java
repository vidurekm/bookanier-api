package online.bookanear.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import online.bookanear.api.dto.FrontendErrorDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Public browser diagnostics endpoint. Log only sanitized diagnostic fields.
 * Simple per-IP limiter is local to this JVM; use an edge limiter in production.
 */
@RestController
@RequestMapping("/api/v1/frontend-errors")
public class FrontendErrorController {
    private static final Logger LOG = LoggerFactory.getLogger(FrontendErrorController.class);
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_EVENTS = 30;
    private static final int MAX_IPS = 10_000;
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+={0,2}");
    private static final Pattern SECRETS = Pattern.compile("(?i)(token|password|secret|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s&;,]+");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?#]+)[?#][^\\s]*");
    private final ConcurrentHashMap<String, Window> limits = new ConcurrentHashMap<>();
    private record Window(long start, int count) { }

    @PostMapping
    public ResponseEntity<Void> report(@Valid @RequestBody FrontendErrorDto event,
                                       HttpServletRequest request) {
        if (!allow(request.getRemoteAddr()))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        LOG.error("[FRONTEND] type={} path={} status={} requestId={} message={}",
                clean(event.type(), 60), pathOnly(event.path()), event.status(),
                clean(event.requestId(), 100), clean(event.message(), 500));
        return ResponseEntity.accepted().build();
    }

    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        if (limits.size() >= MAX_IPS) {
            limits.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
            if (limits.size() >= MAX_IPS && !limits.containsKey(ip)) return false;
        }
        Window value = limits.compute(ip, (key, old) -> old == null || now - old.start() >= WINDOW_MS
                ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        return value.count() <= MAX_EVENTS;
    }

    private static String pathOnly(String path) {
        if (path == null || path.isBlank()) return "-";
        String value = path.split("[?#]", 2)[0];
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try { value = java.net.URI.create(value).getPath(); }
            catch (IllegalArgumentException ex) { return "-"; }
        }
        // Opaque public booking / verification tokens must not appear in logs.
        value = value.replaceAll("/[A-Za-z0-9_-]{24,}", "/[ID]");
        return clean(value, 300);
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return "-";
        String safe = URL_QUERY.matcher(value).replaceAll("$1?[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = SECRETS.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = EMAIL.matcher(safe).replaceAll("[EMAIL]");
        safe = safe.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.length() > max ? safe.substring(0, max) : safe;
    }
}
