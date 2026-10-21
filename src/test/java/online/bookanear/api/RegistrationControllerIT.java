package online.bookanear.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import online.bookanear.api.dto.RegistrationDto;
import online.bookanear.api.service.RegistrationService;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationControllerIT extends PostgresIntegrationTest {
    private static final String REGISTER = "/api/v1/registration";
    private static final String REGISTRATION_BODY = """
            {"organizationName":"Example Studio","ownerName":"Alex Smith",
             "email":"OWNER@EXAMPLE.COM","password":"a-long-strong-password"}
            """;
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registrationService;

    private String registerAndGetToken() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        var message = captor.getValue();
        assertThat(message.getTo()).containsExactly("owner@example.com");
        var match = TOKEN.matcher(message.getText());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void registrationCreatesPendingOrganizationAndSendsHashedToken() throws Exception {
        String token = registerAndGetToken();
        var user = db.queryForMap("SELECT email, name, password_hash, email_verified_at FROM users",
                Map.of());
        assertThat(user.get("email")).isEqualTo("owner@example.com");
        assertThat(user.get("name")).isEqualTo("Alex Smith");
        assertThat(user.get("password_hash").toString()).startsWith("$2");
        assertThat(user.get("password_hash")).isNotEqualTo("a-long-strong-password");
        assertThat(user.get("email_verified_at")).isNull();
        assertThat(db.queryForObject("SELECT registration_status FROM organizations", Map.of(), String.class))
                .isEqualTo("pending_email_verification");
        assertThat(db.queryForObject("SELECT booking_enabled FROM organizations", Map.of(), Boolean.class))
                .isFalse();
        assertThat(db.queryForObject("SELECT role FROM organization_members", Map.of(), String.class))
                .isEqualTo("owner");
        String storedHash = db.queryForObject(
                "SELECT token_hash FROM organization_registration_tokens", Map.of(), String.class);
        assertThat(storedHash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void verificationActivatesOrganizationAndCannotBeReused() throws Exception {
        String token = registerAndGetToken();
        String body = "{\"token\":\"" + token + "\"}";
        mvc.perform(post(REGISTER + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("verified"));
        assertThat(db.queryForObject("SELECT registration_status FROM organizations", Map.of(), String.class))
                .isEqualTo("active");
        assertThat(db.queryForObject("SELECT booking_enabled FROM organizations", Map.of(), Boolean.class))
                .isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE email_verified_at IS NOT NULL",
                Map.of(), Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organization_registration_tokens WHERE consumed_at IS NOT NULL",
                Map.of(), Integer.class)).isEqualTo(1);
        mvc.perform(post(REGISTER + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        // The newly verified owner can now authenticate.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"a-long-strong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void invalidAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(post(REGISTER + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid-token\"}"))
                .andExpect(status().isBadRequest());
        String token = registerAndGetToken();
        db.update("UPDATE organization_registration_tokens SET created_at = now() - interval '3 days', expires_at = now() - interval '2 days'",
                Map.of());
        mvc.perform(post(REGISTER + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT registration_status FROM organizations", Map.of(), String.class))
                .isEqualTo("pending_email_verification");
    }

    @Test
    void duplicateEmailIsRejectedWithConflict() throws Exception {
        registerAndGetToken();
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION_BODY))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organizations", Map.of(), Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidRegistrationPayloadReturnsBadRequest() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"\",\"ownerName\":\"Alex\", \"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mailFailureRollsBackEntireRegistrationTransaction() {
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> registrationService.register(new RegistrationDto.RegisterRequest(
                "Example Studio", "Alex Smith", "owner@example.com", "a-long-strong-password")))
                .isInstanceOf(MailSendException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organizations", Map.of(), Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organization_registration_tokens", Map.of(), Integer.class))
                .isZero();
    }
}
