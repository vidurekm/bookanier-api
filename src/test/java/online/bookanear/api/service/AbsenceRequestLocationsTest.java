package online.bookanear.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.config.OrganizationSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbsenceRequestLocationsTest {
    private final NamedParameterJdbcTemplate db=mock(NamedParameterJdbcTemplate.class);
    private final OrganizationSecurity security=mock(OrganizationSecurity.class);
    private final AbsenceRequestService service=new AbsenceRequestService(db,security,null,new ObjectMapper());
    private final Authentication auth=mock(Authentication.class);

    @Test
    void workerReceivesOnlyAssignedActiveLocations() {
        when(security.canRead(12,auth)).thenReturn(true);
        when(security.canWrite(12,auth)).thenReturn(false);
        when(security.ownMemberId(12,auth)).thenReturn(34L);
        when(db.queryForList(contains("JOIN member_locations"),anyMap())).thenReturn(List.of());

        service.locations(12,auth);

        verify(db).queryForList(contains("m.id=:member AND m.active=true"),anyMap());
    }

    @Test
    void managerReceivesAllActiveLocations() {
        when(security.canRead(12,auth)).thenReturn(true);
        when(security.canWrite(12,auth)).thenReturn(true);
        when(db.queryForList(contains("FROM locations WHERE organization_id=:org AND active=true"),anyMap()))
                .thenReturn(List.of());

        service.locations(12,auth);

        verify(db).queryForList(contains("FROM locations WHERE organization_id=:org AND active=true"),anyMap());
    }
}
