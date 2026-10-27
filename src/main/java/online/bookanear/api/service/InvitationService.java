package online.bookanear.api.service;

import online.bookanear.api.dto.InvitationDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class InvitationService {
    private static final int INVITATION_HOURS = 24;
    private final NamedParameterJdbcTemplate db;
    private final JavaMailSender mailSender;
    private final StaffResourceService staffResourceService;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${registration.home-url:http://localhost:5173/}") private String frontendUrl;
    @Value("${registration.from-email}") private String fromEmail;

    public InvitationService(NamedParameterJdbcTemplate db, JavaMailSender mailSender,
                             StaffResourceService staffResourceService) {
        this.db = db;
        this.mailSender = mailSender;
        this.staffResourceService = staffResourceService;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public Object create(long organizationId, InvitationDto.CreateRequest request) {
        String email = normalizeEmail(request.email());
        String role = request.role() == null ? "worker" : request.role();
        String displayName = request.displayName().trim();
        boolean canManageBookings = Boolean.TRUE.equals(request.canManageBookings());
        boolean canManageOwnBookings = Boolean.TRUE.equals(request.canManageOwnBookings());
        if (canManageBookings && canManageOwnBookings)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "A worker cannot manage both colleagues' bookings and only their own bookings");
        boolean canApproveOwnAbsence = Boolean.TRUE.equals(request.canApproveOwnAbsence());
        Long memberId = request.memberId();
        if (memberId != null) {
            var members = db.queryForList("""
                    SELECT display_name,role,active,user_id,can_manage_bookings,can_manage_own_bookings,can_approve_own_absence,archived_at
                    FROM organization_members WHERE organization_id=:org AND id=:member
                    """, Map.of("org", organizationId, "member", memberId));
            if (members.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Member not found");
            var member = members.getFirst();
            if (member.get("archived_at") != null)
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Member not found");
            if (member.get("user_id") != null)
                throw new ApiException(HttpStatus.CONFLICT, "This member already has a linked account");
            displayName = (String) member.get("display_name");
            role = (String) member.get("role");
            canManageBookings = Boolean.TRUE.equals(member.get("can_manage_bookings"));
            canManageOwnBookings = Boolean.TRUE.equals(member.get("can_manage_own_bookings"));
            canApproveOwnAbsence = Boolean.TRUE.equals(member.get("can_approve_own_absence"));
        }
        var organization = db.queryForList("SELECT name FROM organizations WHERE id=:org",
                Map.of("org", organizationId));
        if (organization.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");
        var matchingUsers = db.queryForList("SELECT id FROM users WHERE lower(email)=:email",
                Map.of("email", email));
        Long userId = matchingUsers.isEmpty() ? null : ((Number) matchingUsers.getFirst().get("id")).longValue();
        if (userId != null && Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_members
                  WHERE organization_id=:org AND user_id=:user)
                """, Map.of("org", organizationId, "user", userId), Boolean.class))) {
            throw new ApiException(HttpStatus.CONFLICT, "This account is already a member of the organization");
        }

        db.update("""
                UPDATE organization_invitations SET consumed_at=now()
                WHERE organization_id=:org AND consumed_at IS NULL
                  AND (lower(email)=:email OR member_id=COALESCE(:member,-1))
                """, new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("email", email).addValue("member", memberId));
        String token = newToken();
        db.update("""
                INSERT INTO organization_invitations
                  (organization_id,member_id,email,display_name,role,can_manage_bookings,can_manage_own_bookings,
                   can_approve_own_absence,token_hash,expires_at)
                VALUES (:org,:member,:email,:name,:role,:manage,:manageOwn,:approve,:hash,:expires)
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("member", memberId).addValue("email", email)
                .addValue("name", displayName).addValue("role", role)
                .addValue("manage", canManageBookings)
                .addValue("manageOwn", canManageOwnBookings)
                .addValue("approve", canApproveOwnAbsence)
                .addValue("hash", hash(token))
                .addValue("expires", OffsetDateTime.now(ZoneOffset.UTC).plusHours(INVITATION_HOURS)));

        String link = FrontendLinks.localized(FrontendLinks.base(frontendUrl), "/accept-invite")
                + "?token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("Pozvánka do organizace " + organization.getFirst().get("name"));
        message.setText("Dobrý den,\n\nbyli jste pozváni do organizace "
                + organization.getFirst().get("name") + ". Pozvánku přijmete otevřením odkazu:\n"
                + link + "\n\nOdkaz platí 24 hodin a lze jej použít pouze jednou.");
        mailSender.send(message);
        return Map.of("status", "sent", "expiresInHours", INVITATION_HOURS);
    }

    @Transactional(readOnly = true)
    public InvitationDto.InvitationInfo info(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}"))
            throw invalidInvitation();
        var rows = db.queryForList("""
                SELECT i.organization_id,i.email,i.display_name,i.role,o.name AS organization_name,
                       EXISTS(SELECT 1 FROM users u WHERE lower(u.email)=lower(i.email)) AS account_exists
                FROM organization_invitations i
                JOIN organizations o ON o.id=i.organization_id
                WHERE i.token_hash=:hash AND i.consumed_at IS NULL AND i.expires_at>now()
                  AND o.registration_status='active'
                """, Map.of("hash", hash(token)));
        if (rows.isEmpty()) throw invalidInvitation();
        var row = rows.getFirst();
        return new InvitationDto.InvitationInfo(((Number) row.get("organization_id")).longValue(),
                (String) row.get("email"),
                (String) row.get("display_name"), (String) row.get("organization_name"),
                (String) row.get("role"), Boolean.TRUE.equals(row.get("account_exists")));
    }

    @Transactional
    public Map<String, String> accept(InvitationDto.AcceptRequest request, Authentication authentication) {
        String digest = hash(request.token());
        var rows = db.queryForList("""
                SELECT i.id,i.organization_id,i.member_id,i.email,i.display_name,i.role,
                       i.can_manage_bookings,i.can_manage_own_bookings,i.can_approve_own_absence
                FROM organization_invitations i
                JOIN organizations o ON o.id=i.organization_id
                WHERE i.token_hash=:hash AND i.consumed_at IS NULL AND i.expires_at>now()
                  AND o.registration_status='active'
                FOR UPDATE OF i
                """, Map.of("hash", digest));
        if (rows.isEmpty()) throw invalidInvitation();
        var invitation = rows.getFirst();
        long organizationId = ((Number) invitation.get("organization_id")).longValue();
        String email = normalizeEmail((String) invitation.get("email"));
        Long authenticatedUserId = authenticatedUserId(authentication);
        Long userId;
        if (authenticatedUserId != null) {
            var user = db.queryForList("SELECT id,email FROM users WHERE id=:id FOR UPDATE",
                    Map.of("id", authenticatedUserId));
            if (user.isEmpty() || !email.equals(normalizeEmail((String) user.getFirst().get("email"))))
                throw new ApiException(HttpStatus.FORBIDDEN, "Invitation email does not match the signed-in account");
            userId = authenticatedUserId;
        } else {
            var existing = db.queryForList("SELECT id FROM users WHERE lower(email)=:email FOR UPDATE",
                    Map.of("email", email));
            if (!existing.isEmpty())
                throw new ApiException(HttpStatus.CONFLICT, "Sign in to the account matching this invitation email");
            if (request.password() == null || request.password().length() < 8)
                throw new ApiException(HttpStatus.BAD_REQUEST, "A password of at least 8 characters is required");
            Long inserted = db.query("""
                    INSERT INTO users(email,name,password_hash,email_verified_at)
                    VALUES (:email,:name,:password,now())
                    ON CONFLICT (lower(email)) DO NOTHING
                    RETURNING id
                    """, new MapSqlParameterSource().addValue("email", email)
                    .addValue("name", invitation.get("display_name"))
                    .addValue("password", passwordEncoder.encode(request.password())),
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (inserted == null)
                throw new ApiException(HttpStatus.CONFLICT, "Sign in to the account matching this invitation email");
            userId = inserted;
        }

        Long memberId = invitation.get("member_id") == null ? null
                : ((Number) invitation.get("member_id")).longValue();
        boolean alreadyMember = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_members
                  WHERE organization_id=:org AND user_id=:user AND id<>COALESCE(:member,-1))
                """, new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("user", userId).addValue("member", memberId), Boolean.class));
        if (alreadyMember) throw new ApiException(HttpStatus.CONFLICT, "This account is already a member of the organization");
        if (memberId == null) {
            db.update("""
                    INSERT INTO organization_members
                      (organization_id,user_id,display_name,role,can_manage_bookings,can_manage_own_bookings,can_approve_own_absence)
                    VALUES (:org,:user,:name,:role,:manage,:manageOwn,:approve)
                    """, new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId)
                    .addValue("name", invitation.get("display_name")).addValue("role", invitation.get("role"))
                    .addValue("manage", invitation.get("can_manage_bookings"))
                    .addValue("manageOwn", invitation.get("can_manage_own_bookings"))
                    .addValue("approve", invitation.get("can_approve_own_absence")));
        } else if (db.update("""
                UPDATE organization_members SET user_id=:user,active=true
                WHERE organization_id=:org AND id=:member AND user_id IS NULL AND archived_at IS NULL
                """, Map.of("user", userId, "org", organizationId, "member", memberId)) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "This member was already linked to an account");
        }
        if (memberId != null) staffResourceService.synchronizeMember(organizationId, memberId);
        db.update("UPDATE organization_invitations SET consumed_at=now() WHERE id=:id",
                Map.of("id", invitation.get("id")));
        return Map.of("status", "accepted");
    }

    @Transactional(readOnly = true)
    public Object pending(long organizationId) {
        return db.queryForList("""
                SELECT id,email,display_name,role,expires_at,created_at
                FROM organization_invitations
                WHERE organization_id=:org AND consumed_at IS NULL AND expires_at>now()
                ORDER BY created_at DESC
                """, Map.of("org", organizationId));
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (!(authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) return null;
        try {
            return Long.parseLong(jwt.getToken().getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidInvitation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invitation is invalid, expired or already used");
    }
}
