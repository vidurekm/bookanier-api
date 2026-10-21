package online.bookanear.api.service;

import online.bookanear.api.dto.PasswordResetDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private final NamedParameterJdbcTemplate db;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String frontendUrl;
    private final String fromEmail;
    private final long tokenMinutes;

    public PasswordResetService(NamedParameterJdbcTemplate db, JavaMailSender mailSender,
            @Value("${registration.home-url:http://localhost:5173/}") String frontendUrl,
            @Value("${registration.from-email}") String fromEmail,
            @Value("${app.security.password-reset.minutes:30}") long tokenMinutes) {
        if (tokenMinutes < 5 || tokenMinutes > 120) throw new IllegalArgumentException("Password reset expiration must be 5–120 minutes");
        this.db = db;
        this.mailSender = mailSender;
        this.frontendUrl = FrontendLinks.base(frontendUrl);
        this.fromEmail = fromEmail;
        this.tokenMinutes = tokenMinutes;
    }

    private static String digest(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    /** Send a reset link only to verified accounts, without disclosing existence to callers. */
    @Transactional
    public PasswordResetDto.Accepted request(PasswordResetDto.Request req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        var users = db.queryForList("""
                SELECT id FROM users WHERE lower(email)=:email AND email_verified_at IS NOT NULL
                FOR UPDATE
                """, Map.of("email", email));
        if (!users.isEmpty()) {
            long user = ((Number)users.getFirst().get("id")).longValue();
            // Limit mail sending: one message per 15 minutes, at most three per 24 hours.
            Integer recent = db.queryForObject("""
                    SELECT count(*) FROM password_reset_tokens
                    WHERE user_id=:id AND created_at > now()-interval '15 minutes'
                    """, Map.of("id", user), Integer.class);
            Integer daily = db.queryForObject("""
                    SELECT count(*) FROM password_reset_tokens
                    WHERE user_id=:id AND created_at > now()-interval '24 hours'
                    """, Map.of("id", user), Integer.class);
            if ((recent == null || recent == 0) && (daily == null || daily < 3)) {
                byte[] bytes = new byte[32];
                secureRandom.nextBytes(bytes);
                String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                db.update("""
                        UPDATE password_reset_tokens SET used_at=now() WHERE user_id=:id AND used_at IS NULL
                        """, Map.of("id", user));
                db.update("""
                        INSERT INTO password_reset_tokens(user_id,token_hash,expires_at)
                        VALUES (:id,:hash,:expiry)
                        """, new MapSqlParameterSource().addValue("id", user)
                        .addValue("hash", digest(raw))
                        .addValue("expiry", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(tokenMinutes),
                                Types.TIMESTAMP_WITH_TIMEZONE));
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail);
                message.setTo(email);
                message.setSubject("Bookanear – obnovení hesla");
                message.setText("Požádali jste o změnu hesla k účtu Bookanear.\n\n"
                        + "Odkaz pro nastavení nového hesla:\n"
                        + FrontendLinks.localized(frontendUrl, "/reset-password") + "?token=" + raw
                        + "\n\nOdkaz platí " + tokenMinutes + " minut a lze jej použít jen jednou."
                        + "\nPokud jste o změnu nežádali, zprávu ignorujte.");
                try {
                    mailSender.send(message);
                } catch (MailException ex) {
                    // Same public response for every address, even if SMTP is unavailable.
                    // Delete the unsent token so a subsequent retry is not throttled.
                    db.update("DELETE FROM password_reset_tokens WHERE token_hash=:hash", Map.of("hash", digest(raw)));
                    log.warn("Password reset email delivery failed (details omitted from public response)");
                }
            }
        }
        return new PasswordResetDto.Accepted("If the account exists, an email has been sent.");
    }

    /** Lock token row and consume it atomically. Also revoke already issued JWTs. */
    @Transactional
    public Map<String, String> confirm(PasswordResetDto.Confirm req) {
        var rows = db.queryForList("""
                SELECT id,user_id FROM password_reset_tokens
                WHERE token_hash=:hash AND used_at IS NULL AND expires_at>now()
                FOR UPDATE
                """, Map.of("hash", digest(req.token())));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Reset link is invalid or expired");
        long user = ((Number) rows.getFirst().get("user_id")).longValue();
        db.update("""
                UPDATE users SET password_hash=:hash,auth_version=auth_version+1 WHERE id=:user
                """, Map.of("hash", passwordEncoder.encode(req.newPassword()), "user", user));
        db.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=:user AND used_at IS NULL", Map.of("user", user));
        return Map.of("status", "password_updated");
    }
}
