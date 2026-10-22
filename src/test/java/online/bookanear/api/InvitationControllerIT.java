package online.bookanear.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.mail.SimpleMailMessage;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InvitationControllerIT extends PostgresIntegrationTest {
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private long createOrganizationAndOwner() {
        long organization = db.queryForObject("""
                INSERT INTO organizations(name,registration_status)
                VALUES ('Example Studio','active') RETURNING id
                """, Map.of(), Long.class);
        long owner = db.queryForObject("""
                INSERT INTO users(email,name,password_hash,email_verified_at)
                VALUES ('owner@example.com','Owner',:password,now()) RETURNING id
                """, Map.of("password", new BCryptPasswordEncoder().encode("owner-password")), Long.class);
        db.update("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role)
                VALUES (:org,:user,'Owner','owner')
                """, Map.of("org", organization, "user", owner));
        return organization;
    }

    private String login(String email, String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String invite(long organization, String bearer, String email) throws Exception {
        clearInvocations(mailSender);
        mvc.perform(post("/api/v1/backoffice/organizations/" + organization + "/invitations")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "displayName", "Alex Worker",
                                "role", "worker", "canManageBookings", true))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly(email);
        var match = TOKEN.matcher(captor.getValue().getText());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @Test
    void newInviteeCreatesVerifiedAccountAndAcceptsOnlyOnce() throws Exception {
        long organization = createOrganizationAndOwner();
        String ownerToken = login("owner@example.com", "owner-password");
        String token = invite(organization, ownerToken, "worker@example.com");

        mvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountExists").value(false))
                .andExpect(jsonPath("$.email").value("worker@example.com"));
        String storedHash = db.queryForObject("SELECT token_hash FROM organization_invitations",
                Map.of(), String.class).trim();
        assertThat(storedHash).hasSize(64).isNotEqualTo(token);
        assertThat(db.queryForObject("""
                SELECT expires_at > now() AND expires_at <= now() + interval '24 hours'
                FROM organization_invitations
                """, Map.of(), Boolean.class)).isTrue();

        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "worker-password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE lower(email)='worker@example.com'",
                Map.of(), Integer.class)).isEqualTo(1);
        var member = db.queryForMap("""
                SELECT m.role,m.can_manage_bookings,u.email_verified_at
                FROM organization_members m JOIN users u ON u.id=m.user_id
                WHERE m.organization_id=:org AND lower(u.email)='worker@example.com'
                """, Map.of("org", organization));
        assertThat(member.get("role")).isEqualTo("worker");
        assertThat(member.get("can_manage_bookings")).isEqualTo(true);
        assertThat(member.get("email_verified_at")).isNotNull();
        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "worker-password"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void existingAccountMustAuthenticateWithTheInvitedEmail() throws Exception {
        long organization = createOrganizationAndOwner();
        db.update("""
                INSERT INTO users(email,name,password_hash,email_verified_at)
                VALUES ('existing@example.com','Existing',:password,now())
                """, Map.of("password", new BCryptPasswordEncoder().encode("existing-password")));
        String ownerToken = login("owner@example.com", "owner-password");
        String token = invite(organization, ownerToken, "existing@example.com");
        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "new-password"))))
                .andExpect(status().isConflict());
        String existingToken = login("existing@example.com", "existing-password");
        mvc.perform(post("/api/v1/invitations/accept").header("Authorization", "Bearer " + existingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE lower(email)='existing@example.com'",
                Map.of(), Integer.class)).isEqualTo(1);

        String secondToken = invite(organization, ownerToken, "wrong-email@example.com");
        mvc.perform(post("/api/v1/invitations/accept").header("Authorization", "Bearer " + existingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", secondToken))))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=:org",
                Map.of("org", organization), Integer.class)).isEqualTo(2);
    }

    @Test
    void invitationCanLinkAnExistingUnassignedMemberWithoutLosingItsRole() throws Exception {
        long organization = createOrganizationAndOwner();
        long member = db.queryForObject("""
                INSERT INTO organization_members(organization_id,display_name,role,can_manage_bookings)
                VALUES (:org,'Legacy Worker','admin',true) RETURNING id
                """, Map.of("org", organization), Long.class);
        String ownerToken = login("owner@example.com", "owner-password");
        clearInvocations(mailSender);
        mvc.perform(post("/api/v1/backoffice/organizations/" + organization + "/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "linked@example.com",
                                "displayName", "Should Be Ignored", "role", "worker", "memberId", member))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        var match = TOKEN.matcher(captor.getValue().getText());
        assertThat(match.find()).isTrue();
        String token = match.group(1);

        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "linked-password"))))
                .andExpect(status().isOk());
        var linked = db.queryForMap("""
                SELECT m.id,m.role,m.display_name,m.can_manage_bookings,u.email
                FROM organization_members m JOIN users u ON u.id=m.user_id
                WHERE m.organization_id=:org AND lower(u.email)='linked@example.com'
                """, Map.of("org", organization));
        assertThat(((Number) linked.get("id")).longValue()).isEqualTo(member);
        assertThat(linked.get("role")).isEqualTo("admin");
        assertThat(linked.get("display_name")).isEqualTo("Legacy Worker");
        assertThat(linked.get("can_manage_bookings")).isEqualTo(true);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=:org",
                Map.of("org", organization), Integer.class)).isEqualTo(2);
    }
}
