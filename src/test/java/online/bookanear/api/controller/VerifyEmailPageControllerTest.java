package online.bookanear.api.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifyEmailPageControllerTest {
    private static final String TOKEN = "01234567890123456789012345678901";

    @Test
    void oldLinkRedirectsToFrontendAndNeverConsumesToken() {
        var page = new VerifyEmailPageController("http://localhost:5173/");
        var response = page.redirect(TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(303);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("http://localhost:5173/cs/verify-email?token="+TOKEN);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void explicitlyConfiguredLocaleIsPreserved() {
        var response = new VerifyEmailPageController("https://bookanear.online/pl/").redirect(TOKEN);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://bookanear.online/pl/verify-email?token="+TOKEN);
    }

    @Test
    void invalidTokenNotRedirectedInQuery() {
        var response = new VerifyEmailPageController("https://bookanear.online/").redirect("evil?foo=bar");
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://bookanear.online/cs/verify-email");
    }

    @Test
    void unsafeFrontendUrlRejected() {
        assertThatThrownBy(() -> new VerifyEmailPageController("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
