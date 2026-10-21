package online.bookanear.api.controller;

import online.bookanear.api.dto.FrontendErrorDto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendErrorControllerTest {
    @Test
    void acceptsStructuredFrontendError() {
        var controller = new FrontendErrorController();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var event = new FrontendErrorDto("api-request", "/admin/prehled", "PUT /setup -> VALIDATION_ERROR", 400, null);
        assertThat(controller.report(event, request).getStatusCode().value()).isEqualTo(202);
    }
    @Test
    void throttlesUntrustedPublicReports() {
        var controller = new FrontendErrorController();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var event = new FrontendErrorDto("api-request", "/", "test", 400, null);
        for (int i = 0; i < 30; i++) assertThat(controller.report(event, request).getStatusCode().value()).isEqualTo(202);
        assertThat(controller.report(event, request).getStatusCode().value()).isEqualTo(429);
    }
}
