package online.bookanear.api.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfig {

    @Bean
    public SecretKey jwtSecretKey(
            @Value("${app.security.jwt.secret}") String secret) {

        byte[] keyBytes = Base64.getDecoder().decode(secret);

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must contain at least 32 bytes"
            );
        }

        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey, DataSource dataSource,
            @Value("${app.security.jwt.issuer}") String issuer) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // Password resets bump auth_version in the DB: all previously issued JWTs are rejected.
        var revocation = (org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt>) jwt -> {
            var claim = jwt.getClaim("auth_version");
            if (!(claim instanceof Number version))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Please log in again", null));
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("SELECT auth_version FROM users WHERE id=?")) {
                statement.setLong(1, Long.parseLong(jwt.getSubject()));
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && rows.getLong(1) == version.longValue())
                        return OAuth2TokenValidatorResult.success();
                }
            } catch (Exception ex) {
                // Fail closed if account state cannot be checked.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Please log in again", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), revocation));

        return decoder;
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(
                new ImmutableSecret<>(jwtSecretKey)
        );
    }
}