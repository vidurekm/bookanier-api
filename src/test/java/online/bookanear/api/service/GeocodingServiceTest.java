package online.bookanear.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import online.bookanear.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GeocodingServiceTest {
    @Test
    void lookupRequestsAndReturnsStructuredAddressWithoutChangingProviderLabel() throws Exception {
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            byte[] body = """
                    [{"display_name":"Provider description","lat":"50.09","lon":"14.45",
                      "address":{"road":"Sokolovská","house_number":"45",
                      "city":"Praha 8","postcode":"18600","country_code":"cz"}}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var service = new GeocodingService(new ObjectMapper(), true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/search", "Bookanear-Test/1.0");
            var result = service.search("Sokolovská 45").getFirst();
            assertTrue(query.get().contains("addressdetails=1"));
            assertEquals("Provider description", result.address());
            assertEquals("cz", result.addressDetails().get("country_code"));
            assertEquals("45", result.addressDetails().get("house_number"));
            assertEquals(50.09, result.latitude());
            assertEquals(14.45, result.longitude());
            assertEquals(result, service.search("Sokolovská 45").getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lookupIsDisabledUnlessExplicitlyEnabled() {
        var service = new GeocodingService(new ObjectMapper(), false,
                "https://nominatim.openstreetmap.org/search", "");
        var error = assertThrows(ApiException.class, () -> service.search("Brno, Česká republika"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status);
    }

    @Test
    void tooShortAddressesAreRejectedWithoutCallingProvider() {
        var service = new GeocodingService(new ObjectMapper(), true,
                "https://nominatim.openstreetmap.org/search", "Bookanear-Test/1.0 (local test)");
        var error = assertThrows(ApiException.class, () -> service.search("abc"));
        assertEquals(HttpStatus.BAD_REQUEST, error.status);
    }
}
