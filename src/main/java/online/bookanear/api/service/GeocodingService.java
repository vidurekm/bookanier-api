package online.bookanear.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in Nominatim lookup for low-volume, explicitly user-triggered searches.
 * Public instance: <=1 query/sec for the *whole app*, identifying UA, cache,
 * no autocomplete; a paid/self-hosted provider is needed for large-scale use.
 * The endpoint base URL can be switched without shipping a new binary.
 */
@Service
public class GeocodingService {
    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private final boolean enabled;
    private final String endpoint;
    private final String userAgent;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private long lastExternalRequest;
    private final Map<String, Cached> cache = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > 500;
        }
    };
    private record Cached(long epochMs, List<Result> results) {}
    public record Result(String address, double latitude, double longitude, Map<String, String> addressDetails) {}

    public GeocodingService(ObjectMapper mapper,
            @Value("${app.geocoding.enabled:false}") boolean enabled,
            @Value("${app.geocoding.endpoint:https://nominatim.openstreetmap.org/search}") String endpoint,
            @Value("${app.geocoding.user-agent:}") String userAgent) {
        this.mapper=mapper;this.enabled=enabled;this.endpoint=endpoint;this.userAgent=userAgent;
    }

    public synchronized List<Result> search(String address) {
        if (!enabled || userAgent.isBlank())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"GEOCODING_DISABLED",
                    "Address lookup is disabled; configure GEOCODING_ENABLED and GEOCODING_USER_AGENT");
        if (address == null || address.strip().length()<5 || address.length()>250)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Enter an address (5-250 characters)");
        String normalized=address.strip().replaceAll("\\s+", " ");
        var key=normalized.toLowerCase(Locale.ROOT);
        long now=System.currentTimeMillis();
        var cached=cache.get(key);
        if (cached!=null && now-cached.epochMs < 86_400_000L) return cached.results;
        // Avoid blocking server threads and exceeding the public service's aggregate limit.
        if (now-lastExternalRequest<1_100L)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"GEOCODING_RATE_LIMIT","Wait a moment before another lookup");
        lastExternalRequest=now;
        try {
            URI uri=URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") +
                "format=jsonv2&addressdetails=1&limit=4&q="+URLEncoder.encode(normalized,StandardCharsets.UTF_8));
            // endpoint is operator-controlled config, never caller-controlled: avoids SSRF.
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("User-Agent",userAgent).header("Accept","application/json")
                .GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode()!=200) {
                log.warn("[GEOCODING] Provider returned HTTP status {}", response.statusCode());
                throw new ApiException(HttpStatus.BAD_GATEWAY,"GEOCODING_UNAVAILABLE","Geocoding provider temporarily unavailable");
            }
            JsonNode root=mapper.readTree(response.body());
            if (!root.isArray()) throw new IllegalStateException("Not an array");
            var list=new ArrayList<Result>();
            for (JsonNode row:root) {
                double lat=Double.parseDouble(row.path("lat").asText());
                double lon=Double.parseDouble(row.path("lon").asText());
                String name=row.path("display_name").asText();
                var details = new LinkedHashMap<String, String>();
                row.path("address").fields().forEachRemaining(field -> {
                    if (field.getValue().isTextual()) details.put(field.getKey(), field.getValue().asText());
                });
                if (!name.isBlank() && Double.isFinite(lat) && Double.isFinite(lon) &&
                    Math.abs(lat)<=90 && Math.abs(lon)<=180) list.add(new Result(name,lat,lon,Map.copyOf(details)));
            }
            var results=List.copyOf(list);
            cache.put(key,new Cached(now,results));
            return results;
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[GEOCODING] Provider request failed: {}", ex.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,"GEOCODING_UNAVAILABLE","Address lookup is temporarily unavailable");
        }
    }
}
