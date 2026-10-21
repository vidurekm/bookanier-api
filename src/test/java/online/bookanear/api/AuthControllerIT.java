package online.bookanear.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthControllerIT extends PostgresIntegrationTest {
    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder jwtDecoder;

    private long insertUser(String email, String password, boolean verified) {
        return db.queryForObject("""
                INSERT INTO users(email, name, password_hash, email_verified_at)
                VALUES (:email, 'Test User', :hash,
                        CASE WHEN :verified THEN now() ELSE NULL END)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("hash", new BCryptPasswordEncoder().encode(password))
                .addValue("verified", verified), Long.class);
    }

    @Test
    void verifiedUserCanLoginAndReceivesValidJwt() throws Exception {
        long id = insertUser("user@example.com", "correct-password", true);
        String body = mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"USER@EXAMPLE.COM","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(604800))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        var jwt = jwtDecoder.decode(json.get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(id));
        assertThat(jwt.getIssuer().toString()).isEqualTo("booking-api");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSameUnauthorizedStatus() throws Exception {
        insertUser("user@example.com", "correct-password", true);
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedEmailCannotLogin() throws Exception {
        insertUser("user@example.com", "correct-password", false);
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"correct-password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidLoginRequestReturnsBadRequest() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void swaggerListsAuthenticationFirstAndDescribesLogin() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("01 - Authentication"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.summary").value("Log in"));
    }
}
