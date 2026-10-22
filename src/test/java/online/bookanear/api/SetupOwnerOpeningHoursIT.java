package online.bookanear.api;

import online.bookanear.api.dto.SetupDto;
import online.bookanear.api.service.SetupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SetupOwnerOpeningHoursIT extends PostgresIntegrationTest {
    @Autowired SetupService setup;

    private long organization() {
        Long user=db.queryForObject("INSERT INTO users(email,name,email_verified_at) VALUES('owner@example.test','Jana',now()) RETURNING id",Map.of(),Long.class);
        Long org=db.queryForObject("INSERT INTO organizations(name,registration_status) VALUES('Salon','active') RETURNING id",Map.of(),Long.class);
        db.update("INSERT INTO organization_members(organization_id,user_id,display_name,role) VALUES(:org,:id,'Jana','owner')",
                Map.of("org",org,"id",user));
        db.update("INSERT INTO organization_booking_config(organization_id) VALUES(:org)",Map.of("org",org));
        return org;
    }

    private SetupDto.Draft draft(boolean ownerWorks) {
        return new SetupDto.Draft("SALON",false,
                List.of(new SetupDto.LocationInput("Hlavní pobočka","Praha","Europe/Prague")),
                List.of(new SetupDto.MemberInput("Marie – recepce",List.of(0),false,null,null,"marie@example.test"),
                        new SetupDto.MemberInput("Petr",List.of(0),true,null,null,"petr@example.test")),
                List.of(new SetupDto.ServiceInput("Stříhání",45,BigDecimal.valueOf(450),"CZK")),
                false,1,1,"Křeslo",false,true,false,12,"09:00","17:00",ownerWorks,0,
                List.of(new SetupDto.OpeningIntervalInput(0,1,"09:00","12:00"),
                        new SetupDto.OpeningIntervalInput(0,1,"13:00","18:00")));
    }

    @Test
    void ownerIsNotDuplicatedAndMondayHasTwoOpeningIntervals() {
        long org=organization();
        setup.finish(org,draft(true));
        assertThat(db.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id=:org AND role='owner'",
                Map.of("org",org),Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM schedule_rules WHERE organization_id=:org AND kind='open' AND weekday=1",
                Map.of("org",org),Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM resources WHERE organization_id=:org AND member_id IS NOT NULL",
                Map.of("org",org),Long.class)).isEqualTo(2); // owner + Petr
        assertThat(db.queryForObject("SELECT count(*) FROM worker_services WHERE organization_id=:org",
                Map.of("org",org),Long.class)).isEqualTo(2);
        assertThat(db.queryForList("""
                SELECT i.email,i.member_id,m.display_name,m.user_id
                FROM organization_invitations i
                JOIN organization_members m ON m.organization_id=i.organization_id AND m.id=i.member_id
                WHERE i.organization_id=:org ORDER BY i.email
                """, Map.of("org",org))).hasSize(2);
        ArgumentCaptor<SimpleMailMessage> emails = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(emails.capture());
        assertThat(emails.getAllValues()).extracting(message -> message.getTo()[0])
                .containsExactlyInAnyOrder("marie@example.test","petr@example.test");
    }

    @Test
    void receptionIsNotBookableAndOwnerCanOptOut() {
        long org=organization();
        setup.finish(org,draft(false));
        assertThat(db.queryForObject("SELECT count(*) FROM resources WHERE organization_id=:org AND member_id IS NOT NULL",
                Map.of("org",org),Long.class)).isEqualTo(1); // only Petr
        assertThat(db.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id=:org",
                Map.of("org",org),Long.class)).isEqualTo(3); // owner + receptionist + Petr
        assertThat(db.queryForObject("SELECT count(*) FROM organization_invitations WHERE organization_id=:org",
                Map.of("org",org),Long.class)).isEqualTo(2);
    }
}
