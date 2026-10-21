package online.bookanear.api;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Each concrete test class starts a disposable PostgreSQL 16 container.
 * Flyway creates the schema; Docker/Testcontainers removes the database afterwards.
 * The SMTP gateway is mocked: integration tests never send real email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class PostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("booking_test")
                    .withUsername("booking_test")
                    .withPassword("booking_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("app.security.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    NamedParameterJdbcTemplate db;

    @BeforeEach
    void clearDatabase() {
        db.getJdbcTemplate().execute(
                "TRUNCATE TABLE organization_registration_tokens, organization_members, " +
                "organizations, users RESTART IDENTITY CASCADE");
    }
}
