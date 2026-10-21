package online.bookanear.api.service;

import online.bookanear.api.dto.AuthDto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final NamedParameterJdbcTemplate db;
    private final JwtEncoder jwtEncoder;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String issuer;
    private final long expirationMinutes;
    public AuthService(NamedParameterJdbcTemplate db, JwtEncoder jwtEncoder,
        @Value("${app.security.jwt.issuer}") String issuer,
        @Value("${app.security.jwt.expiration-minutes:30}") long expirationMinutes) {
        this.db=db; this.jwtEncoder=jwtEncoder; this.issuer=issuer; this.expirationMinutes=expirationMinutes;
    }
    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        var users = db.queryForList("""
                SELECT id, password_hash, email_verified_at, auth_version
                FROM users
                WHERE lower(email) = :email
                """, Map.of("email", email));

        // Stejná odpověď pro neexistující účet i nesprávné heslo.
        if (users.size() != 1) {
            throw invalidCredentials();
        }

        var user = users.get(0);
        String hash = (String) user.get("password_hash");
        if (hash == null || !passwordEncoder.matches(request.password(), hash)) {
            throw invalidCredentials();
        }
        if (user.get("email_verified_at") == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email is not verified");
        }

        long userId = ((Number) user.get("id")).longValue();
        Instant now = Instant.now();
        long expiresIn = Duration.ofMinutes(expirationMinutes).toSeconds();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Long.toString(userId))
                .claim("auth_version", ((Number)user.get("auth_version")).longValue())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(expiresIn))
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims
        )).getTokenValue();

        return new AuthDto.LoginResponse(token, "Bearer", expiresIn);
    }
}
