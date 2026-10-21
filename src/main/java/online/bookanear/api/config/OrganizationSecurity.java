package online.bookanear.api.config;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Checks membership of the authenticated JWT subject in the requested organization.
 */
@Component("organizationSecurity")
public class OrganizationSecurity {
    private final NamedParameterJdbcTemplate db;

    public OrganizationSecurity(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    public boolean isSuperadmin(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return false;
        Boolean result = db.queryForObject("""
                SELECT is_superadmin FROM users
                WHERE id=:user AND email_verified_at IS NOT NULL
                """, Map.of("user", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public boolean canRead(long organizationId, Authentication authentication) {
        return hasRole(organizationId, authentication, false, false);
    }

    public boolean canWrite(long organizationId, Authentication authentication) {
        return hasRole(organizationId, authentication, true, false);
    }

    public boolean isOwner(long organizationId, Authentication authentication) {
        return hasRole(organizationId, authentication, true, true);
    }

    /** Booking management is an independent permission (e.g. a working hairdresser handling calls). */
    public boolean canManageBookings(long organizationId, Authentication authentication) {
        if (canWrite(organizationId, authentication)) return true;
        Long userId = authenticatedUserId(authentication);
        if (userId == null || !canRead(organizationId, authentication)) return false;
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_members
                  WHERE organization_id=:org AND user_id=:user AND active=true AND can_manage_bookings)
                """,Map.of("org",organizationId,"user",userId),Boolean.class));
    }

    public boolean canApproveOwnAbsence(long organizationId, long memberId, Authentication authentication) {
        if (canWrite(organizationId, authentication)) return true;
        Long userId=authenticatedUserId(authentication);
        if (userId==null || !canRead(organizationId,authentication)) return false;
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_members WHERE organization_id=:org
                 AND id=:member AND user_id=:user AND active=true AND can_approve_own_absence)
                """,Map.of("org",organizationId,"member",memberId,"user",userId),Boolean.class));
    }

    public Long ownMemberId(long organizationId, Authentication authentication) {
        Long userId=authenticatedUserId(authentication);
        if (userId==null || !canRead(organizationId,authentication)) return null;
        var found=db.queryForList("SELECT id FROM organization_members WHERE organization_id=:org AND user_id=:user AND active=true LIMIT 1",
                Map.of("org",organizationId,"user",userId));
        return found.isEmpty()?null:((Number)found.getFirst().get("id")).longValue();
    }

    /**
     * Owner/admin can access any member; worker only their own membership.
     */
    public boolean canAccessMember(long organizationId, long memberId, Authentication authentication) {
        if (canWrite(organizationId, authentication)) return true;
        Long userId = authenticatedUserId(authentication);
        if (userId == null || !canRead(organizationId, authentication)) return false;
        Integer count = db.queryForObject("""
                SELECT count(*) FROM organization_members
                WHERE organization_id=:org AND id=:member AND user_id=:user AND active=true
                """, Map.of("org", organizationId, "member", memberId, "user", userId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Workers may see only reservations assigned to them.
     */
    public boolean canAccessReservation(long organizationId, long reservationId, Authentication authentication) {
        if (canManageBookings(organizationId, authentication)) return true;
        Long userId = authenticatedUserId(authentication);
        if (userId == null || !canRead(organizationId, authentication)) return false;
        Integer count = db.queryForObject("""
                SELECT count(*) FROM reservation_items ri
                WHERE ri.organization_id=:org AND ri.reservation_id=:reservation AND (
                  EXISTS (SELECT 1 FROM organization_members m
                          WHERE m.organization_id=ri.organization_id AND m.id=ri.member_id
                            AND m.user_id=:user AND m.active=true)
                  OR EXISTS (SELECT 1 FROM reservation_resources rr
                             JOIN resources res ON res.organization_id=rr.organization_id
                                               AND res.id=rr.resource_id
                             JOIN organization_members m ON m.organization_id=res.organization_id
                                                        AND m.id=res.member_id
                             WHERE rr.organization_id=ri.organization_id AND rr.reservation_item_id=ri.id
                               AND m.user_id=:user AND m.active=true)
                )
                """, Map.of("org", organizationId, "reservation", reservationId, "user", userId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Worker may register an absence for their own member ID; admin/owner for anyone.
     */
    public boolean canCreateAbsence(long organizationId, Long memberId, Authentication authentication) {
        if (canWrite(organizationId, authentication)) return true;
        return false; // Workers submit requests through the approval workflow.
    }

    public Long authenticatedUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth) || !authentication.isAuthenticated()) return null;
        try {
            return Long.parseLong(jwtAuth.getToken().getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    public boolean isWorker(long organizationId, Authentication authentication) {
        if (!canRead(organizationId, authentication)) return false;
        return !canWrite(organizationId, authentication);
    }

    private boolean hasRole(long organizationId, Authentication authentication,
                            boolean write, boolean ownerOnly) {
        if (isSuperadmin(authentication)) return true;
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)
                || !authentication.isAuthenticated()) return false;

        final long userId;
        try {
            userId = Long.parseLong(jwtAuth.getToken().getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }

        var roles = db.queryForList("""
                SELECT m.role
                FROM organization_members m
                JOIN users u ON u.id = m.user_id
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.organization_id = :org
                  AND m.user_id = :user
                  AND m.active = true
                  AND u.email_verified_at IS NOT NULL
                  AND o.registration_status = 'active'
                """, Map.of("org", organizationId, "user", userId));

        return roles.stream().anyMatch(row -> {
            String role = String.valueOf(row.get("role"));
            if (ownerOnly) return "owner".equals(role);
            if (write) return "owner".equals(role) || "admin".equals(role);
            return "owner".equals(role) || "admin".equals(role) || "worker".equals(role);
        });
    }
}
