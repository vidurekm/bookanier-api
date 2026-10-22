package online.bookanear.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetControllerIT extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder jwtDecoder;
    private static final String PASSWORD = "/api/v1/auth/password";
    private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{32,128})");

    private long user(boolean verified) {
        return db.queryForObject("""
                INSERT INTO users(email,name,password_hash,email_verified_at)
                VALUES('worker@example.test','Worker',:hash,CASE WHEN :verified THEN now() ELSE NULL END)
                RETURNING id
                """,new MapSqlParameterSource().addValue("hash",new BCryptPasswordEncoder().encode("original-password"))
                .addValue("verified",verified),Long.class);
    }
    private String requestReset() throws Exception {
        mvc.perform(post(PASSWORD+"/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"worker@example.test\"}"))
                .andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        var matcher = LINK_TOKEN.matcher(captor.getValue().getText());
        assertThat(matcher.find()).isTrue();
        assertThat(captor.getValue().getText()).contains("/cs/reset-password?token=");
        return matcher.group(1);
    }
    @Test
    void unknownEmailDoesNotRevealAccountAndDoesNotSendMail() throws Exception {
        mvc.perform(post(PASSWORD+"/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.test\"}"))
                .andExpect(status().isAccepted());
        verifyNoInteractions(mailSender);
    }
    @Test
    void resetEmailUsesTheLanguageSelectedOnTheRequest() throws Exception {
        user(true);
        mvc.perform(post(PASSWORD+"/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"worker@example.test\",\"language\":\"en\"}"))
                .andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        var message = captor.getValue();
        assertThat(message.getSubject()).isEqualTo("Bookanear password reset");
        assertThat(message.getText()).contains("A password change was requested",
                "http://localhost:5173/en/reset-password?token=", "valid for 30 minutes");
    }
    @Test
    void verifiedUserCanResetOnceAndOldJwtBecomesInvalid() throws Exception {
        long user = user(true);
        String jwtJson = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"worker@example.test\",\"password\":\"original-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jwt = mapper.readTree(jwtJson).get("accessToken").asText();
        assertThat(jwtDecoder.decode(jwt).getSubject()).isEqualTo(Long.toString(user));
        String token=requestReset();
        // Regression: the PostgreSQL timestamptz value must be bound and stored correctly.
        OffsetDateTime expiresAt = db.queryForObject(
                "SELECT expires_at FROM password_reset_tokens WHERE user_id=:id",
                Map.of("id", user), OffsetDateTime.class);
        assertThat(expiresAt).isBetween(
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(29),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(31));
        assertThat(db.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE user_id=:id",
                Map.of("id",user),String.class)).doesNotContain(token).hasSize(64);
        mvc.perform(post(PASSWORD+"/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"newPassword\":\"new-secret-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("password_updated"));
        assertThatThrownBy(()->jwtDecoder.decode(jwt)).isInstanceOf(Exception.class);
        mvc.perform(post(PASSWORD+"/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"newPassword\":\"another-password\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"worker@example.test\",\"password\":\"original-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"worker@example.test\",\"password\":\"new-secret-password\"}"))
                .andExpect(status().isOk());
    }
    @Test
    void forgotPasswordRejectsEmailWithoutDottedDomain() throws Exception {
        mvc.perform(post(PASSWORD+"/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"df@s\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailSender);
    }
    @Test
    void resetPasswordAllowsEightButNotSevenCharacters() throws Exception {
        long id = user(true);
        String token = requestReset();
        mvc.perform(post(PASSWORD+"/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"newPassword\":\"1234567\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PASSWORD+"/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"newPassword\":\"12345678\"}"))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id=:id AND used_at IS NOT NULL",
                Map.of("id", id), Integer.class)).isEqualTo(1);
    }
    @Test
    void unverifiedAccountCannotRequestPasswordReset() throws Exception {
        user(false);
        mvc.perform(post(PASSWORD+"/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"worker@example.test\"}"))
                .andExpect(status().isAccepted());
        verifyNoInteractions(mailSender);
    }
    @Test
    void expiredResetIsRejected() throws Exception {
        user(true);
        String token=requestReset();
        db.update("UPDATE password_reset_tokens SET expires_at=now()-interval '1 minute'",Map.of());
        mvc.perform(post(PASSWORD+"/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\",\"newPassword\":\"new-secret-password\"}"))
                .andExpect(status().isBadRequest());
    }
}
