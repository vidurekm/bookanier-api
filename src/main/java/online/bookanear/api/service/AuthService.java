package online.bookanear.api.service;

import online.bookanear.api.dto.AuthDto;
import online.bookanear.api.dto.AccountDto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final NamedParameterJdbcTemplate db;
    private final JwtEncoder jwtEncoder;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JavaMailSender mailSender;
    private final String issuer;
    private final long expirationMinutes;
    private final String frontendUrl;
    private final String fromEmail;
    private final OrganizationDeletionService organizationDeletionService;
    private final SecureRandom secureRandom = new SecureRandom();
    public AuthService(NamedParameterJdbcTemplate db, JwtEncoder jwtEncoder, JavaMailSender mailSender,
        @Value("${app.security.jwt.issuer}") String issuer,
        @Value("${app.security.jwt.expiration-minutes:30}") long expirationMinutes,
        @Value("${registration.home-url:http://localhost:5173/}") String frontendUrl,
        @Value("${registration.from-email}") String fromEmail,
        OrganizationDeletionService organizationDeletionService) {
        this.db=db; this.jwtEncoder=jwtEncoder; this.mailSender=mailSender; this.issuer=issuer;
        this.expirationMinutes=expirationMinutes; this.frontendUrl=FrontendLinks.base(frontendUrl); this.fromEmail=fromEmail;
        this.organizationDeletionService=organizationDeletionService;
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

    public AuthDto.CurrentUser currentUser(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        final long userId;
        try {
            userId = Long.parseLong(jwt.getToken().getSubject());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        var users = db.queryForList("""
                SELECT name,email,(SELECT new_email FROM account_email_change_tokens
                    WHERE user_id=users.id AND consumed_at IS NULL AND expires_at>now()
                    ORDER BY created_at DESC LIMIT 1) AS pending_email
                FROM users
                WHERE id=:user AND email_verified_at IS NOT NULL
                """, Map.of("user", userId));
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        var user = users.getFirst();
        return new AuthDto.CurrentUser((String)user.get("name"),(String)user.get("email"),(String)user.get("pending_email"));
    }

    private long authenticatedUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        try {
            return Long.parseLong(jwt.getToken().getSubject());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    @Transactional
    public AuthDto.CurrentUser updateName(Authentication authentication, AccountDto.UpdateName request) {
        long userId=authenticatedUserId(authentication);
        int updated=db.update("UPDATE users SET name=:name WHERE id=:id AND email_verified_at IS NOT NULL",
                Map.of("name",request.name().trim(),"id",userId));
        if(updated!=1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        return currentUser(authentication);
    }

    @Transactional
    public void deleteAccount(Authentication authentication) {
        long userId = authenticatedUserId(authentication);
        var ownedOrganizations = db.queryForList("""
                SELECT DISTINCT organization_id
                FROM organization_members
                WHERE user_id=:user AND role='owner'
                """, Map.of("user", userId));
        for (var organization : ownedOrganizations) {
            long organizationId = ((Number) organization.get("organization_id")).longValue();
            organizationDeletionService.delete(organizationId);
        }

        db.update("UPDATE organization_members SET user_id=NULL, active=false WHERE user_id=:user",
                Map.of("user", userId));
        db.update("UPDATE customers SET user_id=NULL WHERE user_id=:user", Map.of("user", userId));
        db.update("UPDATE member_absence_requests SET requested_by=NULL WHERE requested_by=:user",
                Map.of("user", userId));
        db.update("UPDATE member_absence_requests SET decided_by=NULL WHERE decided_by=:user",
                Map.of("user", userId));
        db.update("UPDATE reservation_events SET actor_user_id=NULL WHERE actor_user_id=:user",
                Map.of("user", userId));
        int deleted = db.update("DELETE FROM users WHERE id=:user", Map.of("user", userId));
        if (deleted != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    private static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional
    public Map<String,String> requestEmailChange(Authentication authentication, AccountDto.ChangeEmail request) {
        long userId=authenticatedUserId(authentication);
        String email=request.email().trim().toLowerCase(Locale.ROOT);
        String language=request.language()==null ? "en" : request.language();
        if(!Set.of("cs","sk","pl","en").contains(language))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported email language");
        var rows=db.queryForList("SELECT email,password_hash FROM users WHERE id=:id AND email_verified_at IS NOT NULL FOR UPDATE",Map.of("id",userId));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        var user=rows.getFirst();
        if(user.get("password_hash")==null || !passwordEncoder.matches(request.currentPassword(),(String)user.get("password_hash")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Current password is incorrect");
        if(email.equalsIgnoreCase((String)user.get("email")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email address is unchanged");
        Long taken=db.queryForObject("SELECT count(*) FROM users WHERE lower(email)=:email AND id<>:id",
                Map.of("email",email,"id",userId),Long.class);
        if(taken!=null && taken>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Email address is already in use");
        db.update("UPDATE account_email_change_tokens SET consumed_at=now() WHERE user_id=:id AND consumed_at IS NULL",Map.of("id",userId));
        byte[] bytes=new byte[32];
        secureRandom.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("""
                INSERT INTO account_email_change_tokens(user_id,new_email,token_hash,expires_at)
                VALUES(:id,:email,:hash,:expires)
                """,new MapSqlParameterSource().addValue("id",userId).addValue("email",email)
                .addValue("hash",digest(token)).addValue("expires",OffsetDateTime.now(ZoneOffset.UTC).plusHours(24),Types.TIMESTAMP_WITH_TIMEZONE));
        String link=FrontendLinks.localized(frontendUrl,language,"/verify-account-email")+"?token="+token;
        String subject=switch(language) {
            case "cs" -> "Potvrďte změnu e-mailu v Bookanear";
            case "sk" -> "Potvrďte zmenu e-mailu v Bookanear";
            case "pl" -> "Potwierdź zmianę adresu e-mail w Bookanear";
            default -> "Confirm your Bookanear email change";
        };
        String body=switch(language) {
            case "cs" -> "Požádali jste o změnu e-mailu k účtu Bookanear. Potvrďte novou adresu do 24 hodin:\n\n"+link+"\n\nPokud jste změnu nepožadovali, zprávu ignorujte.";
            case "sk" -> "Požiadali ste o zmenu e-mailu k účtu Bookanear. Potvrďte novú adresu do 24 hodín:\n\n"+link+"\n\nAk ste zmenu nepožadovali, správu ignorujte.";
            case "pl" -> "Poproszono o zmianę adresu e-mail konta Bookanear. Potwierdź nowy adres w ciągu 24 godzin:\n\n"+link+"\n\nJeśli nie prosisz o tę zmianę, zignoruj wiadomość.";
            default -> "A change to your Bookanear account email was requested. Confirm the new address within 24 hours:\n\n"+link+"\n\nIf you did not request this change, ignore this message.";
        };
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        return Map.of("status","confirmation_sent");
    }

    @Transactional
    public Map<String,String> confirmEmailChange(AccountDto.ConfirmEmail request) {
        var rows=db.queryForList("""
                SELECT id,user_id,new_email FROM account_email_change_tokens
                WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at>now()
                FOR UPDATE
                """,Map.of("hash",digest(request.token())));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email confirmation link is invalid or expired");
        var token=rows.getFirst();
        long userId=((Number)token.get("user_id")).longValue();
        Long alreadyUsed=db.queryForObject("SELECT count(*) FROM users WHERE lower(email)=lower(:email) AND id<>:id",
                Map.of("email",token.get("new_email"),"id",userId),Long.class);
        if(alreadyUsed!=null && alreadyUsed>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Email address is already in use");
        db.update("UPDATE users SET email=:email WHERE id=:id",
                Map.of("email",token.get("new_email"),"id",userId));
        db.update("UPDATE account_email_change_tokens SET consumed_at=now() WHERE user_id=:id AND consumed_at IS NULL",
                Map.of("id",userId));
        return Map.of("status","email_updated");
    }

    @Transactional
    public Map<String,String> changePassword(Authentication authentication, AccountDto.ChangePassword request) {
        long userId=authenticatedUserId(authentication);
        var rows=db.queryForList("SELECT password_hash FROM users WHERE id=:id AND email_verified_at IS NOT NULL FOR UPDATE",Map.of("id",userId));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(rows.getFirst().get("password_hash")==null || !passwordEncoder.matches(request.currentPassword(),(String)rows.getFirst().get("password_hash")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Current password is incorrect");
        db.update("UPDATE users SET password_hash=:hash,auth_version=auth_version+1 WHERE id=:id",
                Map.of("hash",passwordEncoder.encode(request.newPassword()),"id",userId));
        return Map.of("status","password_updated");
    }
}
