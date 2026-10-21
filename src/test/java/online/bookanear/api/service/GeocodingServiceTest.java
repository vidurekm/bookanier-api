package online.bookanear.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class GeocodingServiceTest {
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
