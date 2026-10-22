package online.bookanear.api.service;

import online.bookanear.api.dto.RegistrationDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private final NamedParameterJdbcTemplate db;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @Value("${registration.home-url:http://localhost:5173/}") private String frontendUrl;
    @Value("${registration.from-email}") private String fromEmail;
    @Value("${registration.token-hours:24}") private long tokenHours;
    private record VerificationEmail(String subject, String text) {}

    private static VerificationEmail verificationEmail(String language, String ownerName,
                                                       String link, long tokenHours) {
        return switch (language) {
            case "sk" -> new VerificationEmail("Potvrďte svoj účet Bookanear",
                    "Dobrý deň, " + ownerName + ",\n\n"
                    + "ďakujeme, že ste si vybrali rezervačný systém Bookanear.\n"
                    + "Na aktiváciu účtu a dokončenie registrácie potvrďte svoju e-mailovú adresu:\n\n"
                    + "POTVRDIŤ E-MAIL: " + link + "\n\n"
                    + "Odkaz je platný " + tokenHours + " hodín a možno ho použiť iba raz. "
                    + "Ak ste registráciu nezačali vy, tento e-mail môžete ignorovať.\n\n"
                    + "S pozdravom\nTím Bookanear");
            case "pl" -> new VerificationEmail("Potwierdź swoje konto Bookanear",
                    "Dzień dobry, " + ownerName + ",\n\n"
                    + "Dziękujemy za wybór systemu rezerwacji Bookanear.\n"
                    + "Aby aktywować konto i dokończyć rejestrację, potwierdź swój adres e-mail:\n\n"
                    + "POTWIERDŹ ADRES E-MAIL: " + link + "\n\n"
                    + "Link jest ważny przez " + tokenHours + " godzin i można go użyć tylko raz. "
                    + "Jeśli rejestracja nie została przez Ciebie rozpoczęta, możesz zignorować tę wiadomość.\n\n"
                    + "Pozdrawiamy\nZespół Bookanear");
            case "en" -> new VerificationEmail("Verify your Bookanear account",
                    "Hello " + ownerName + ",\n\n"
                    + "Thank you for choosing the Bookanear booking system.\n"
                    + "To activate your account and complete registration, please verify your email address:\n\n"
                    + "VERIFY EMAIL ADDRESS: " + link + "\n\n"
                    + "This link is valid for " + tokenHours + " hours and can only be used once. "
                    + "If you did not start this registration, you can safely ignore this email.\n\n"
                    + "Kind regards,\nThe Bookanear team");
            default -> new VerificationEmail("Potvrďte svůj účet Bookanear",
                    "Dobrý den, " + ownerName + ",\n\n"
                    + "děkujeme, že jste si vybrali rezervační systém Bookanear.\n"
                    + "Pro aktivaci účtu a dokončení registrace potvrďte svou e-mailovou adresu:\n\n"
                    + "POTVRDIT E-MAIL: " + link + "\n\n"
                    + "Odkaz platí " + tokenHours + " hodin a lze jej použít pouze jednou. "
                    + "Pokud jste registraci nezahájili, můžete tento e-mail bezpečně ignorovat.\n\n"
                    + "S pozdravem\nTým Bookanear");
        };
    }

    public RegistrationService(NamedParameterJdbcTemplate db, JavaMailSender mailSender) {
        this.db=db; this.mailSender=mailSender;
    }
    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public Map<String, String> register(RegistrationDto.RegisterRequest req) {
        String language = req.language() == null ? "cs" : req.language();
        if (!Set.of("cs", "sk", "pl", "en").contains(language))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported email language");
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        var p = new MapSqlParameterSource().addValue("email", email).addValue("name", req.ownerName().trim()).addValue("password", passwordEncoder.encode(req.password()));
        // A pre-existing email must be handled by the account-linking workflow, not silently overwritten.
        Long userId = db.queryForObject("""
                INSERT INTO users(email,name,password_hash)
                VALUES (:email,:name,:password) RETURNING id
                """, p, Long.class);
        Long orgId = db.queryForObject("""
                INSERT INTO organizations(name,email,booking_enabled,registration_status)
                VALUES (:name,:email,false,'pending_email_verification') RETURNING id
                """, new MapSqlParameterSource().addValue("name", req.organizationName().trim()).addValue("email", email), Long.class);
        db.update("INSERT INTO organization_booking_config(organization_id) VALUES(:org)",Map.of("org",orgId));
        db.update("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role)
                VALUES (:org,:user,:name,'owner')
                """, new MapSqlParameterSource().addValue("org", orgId).addValue("user", userId).addValue("name", req.ownerName().trim()));
        String token = newToken();
        db.update("""
                INSERT INTO organization_registration_tokens(organization_id,user_id,token_hash,expires_at)
                VALUES (:org,:user,:hash,:expires)
                """, new MapSqlParameterSource().addValue("org", orgId).addValue("user", userId).addValue("hash", hash(token)).addValue("expires", OffsetDateTime.now(ZoneOffset.UTC).plusHours(tokenHours)));
        String link = FrontendLinks.localized(FrontendLinks.base(frontendUrl), language, "/verify-email") + "?token=" + token;
        VerificationEmail copy = verificationEmail(language, req.ownerName().trim(), link, tokenHours);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject(copy.subject());
        message.setText(copy.text());
        mailSender.send(message);
        return Map.of("message", "Registration created; check your email");
    }

    @Transactional
    public Map<String, String> verify(RegistrationDto.VerifyRequest req) {
        String digest = hash(req.token());
        var rows = db.queryForList("""
                SELECT id,organization_id,user_id FROM organization_registration_tokens
                WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at > now()
                FOR UPDATE
                """, Map.of("hash", digest));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token");
        var row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        long org = ((Number) row.get("organization_id")).longValue();
        long user = ((Number) row.get("user_id")).longValue();
        db.update("UPDATE organization_registration_tokens SET consumed_at=now() WHERE id=:id", Map.of("id", id));
        db.update("UPDATE users SET email_verified_at=COALESCE(email_verified_at,now()) WHERE id=:id", Map.of("id", user));
        db.update("""
                UPDATE organizations SET registration_status='active',verified_at=now(),booking_enabled=true
                WHERE id=:id AND registration_status='pending_email_verification'
                """, Map.of("id", org));
        return Map.of("status", "verified");
    }
}
