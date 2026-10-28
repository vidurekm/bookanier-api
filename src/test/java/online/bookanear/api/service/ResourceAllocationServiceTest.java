package online.bookanear.api.service;

import online.bookanear.api.dto.BookingDto;
import online.bookanear.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAllocationServiceTest {
    private final NamedParameterJdbcTemplate db=mock(NamedParameterJdbcTemplate.class);
    private final ResourceAllocationService service=new ResourceAllocationService(db);

    @Test
    void allocatesEverySelectedWorkerForAMultiStaffService() {
        when(db.queryForMap(contains("customer_can_choose_staff"),anyMap()))
                .thenReturn(Map.of("customer_can_choose_staff",true,"customer_can_choose_resource",false));
        when(db.queryForList(contains("SELECT req.resource_type_id"),anyMap()))
                .thenReturn(List.of(Map.of("resource_type_id",5L,"quantity",2,"category","staff")));
        when(db.queryForList(contains("SELECT r.id, r.member_id"),any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id",100L,"member_id",10L),Map.of("id",101L,"member_id",11L)));
        var request=new BookingDto.CreateUniversalBooking(1L,2L,3L,
                OffsetDateTime.parse("2027-01-01T10:00:00Z"),null,null,null,null,null,
                List.of(10L,11L),"Customer",null,null);

        var allocations=service.allocate(1,2,3,request.startsAt(),request.startsAt().plusHours(1),
                request,member->true,true);

        assertThat(allocations).extracting(ResourceAllocationService.Allocation::memberId)
                .containsExactly(10L,11L);
    }

    @Test
    void automaticallyAllocatesEveryRequiredWorkerWhenCustomerChoiceIsDisabled() {
        when(db.queryForMap(contains("customer_can_choose_staff"),anyMap()))
                .thenReturn(Map.of("customer_can_choose_staff",false,"customer_can_choose_resource",false));
        when(db.queryForList(contains("SELECT req.resource_type_id"),anyMap()))
                .thenReturn(List.of(Map.of("resource_type_id",5L,"quantity",2,"category","staff")));
        when(db.queryForList(contains("SELECT r.id, r.member_id"),any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id",100L,"member_id",10L),Map.of("id",101L,"member_id",11L)));
        var request=new BookingDto.CreateUniversalBooking(1L,2L,3L,
                OffsetDateTime.parse("2027-01-01T10:00:00Z"),null,null,null,null,null,
                List.of(),"Customer",null,null);

        var allocations=service.allocate(1,2,3,request.startsAt(),request.startsAt().plusHours(1),
                request,member->true,true);

        assertThat(allocations).extracting(ResourceAllocationService.Allocation::memberId)
                .containsExactly(10L,11L);
    }

    @Test
    void rejectsMissingWorkerSelectionsWhenCustomerChoiceIsEnabled() {
        when(db.queryForMap(contains("customer_can_choose_staff"),anyMap()))
                .thenReturn(Map.of("customer_can_choose_staff",true,"customer_can_choose_resource",false));
        when(db.queryForList(contains("SELECT req.resource_type_id"),anyMap()))
                .thenReturn(List.of(Map.of("resource_type_id",5L,"quantity",2,"category","staff")));
        var request=new BookingDto.CreateUniversalBooking(1L,2L,3L,
                OffsetDateTime.parse("2027-01-01T10:00:00Z"),null,null,null,null,null,
                List.of(),"Customer",null,null);

        assertThatThrownBy(() -> service.allocate(1,2,3,request.startsAt(),
                request.startsAt().plusHours(1),request,member->true,true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Select all required staff members");
    }

    @Test
    void allowsBackofficeWorkerChoiceWhenServiceHasNoStaffResourceRequirement() {
        when(db.queryForMap(contains("customer_can_choose_staff"),anyMap()))
                .thenReturn(Map.of("customer_can_choose_staff",false,"customer_can_choose_resource",false));
        when(db.queryForList(contains("SELECT req.resource_type_id"),anyMap()))
                .thenReturn(List.of(Map.of("resource_type_id",5L,"quantity",1,"category","equipment")));
        when(db.queryForList(contains("SELECT r.id, r.member_id"),any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id",100L)));
        var request=new BookingDto.CreateUniversalBooking(1L,2L,3L,
                OffsetDateTime.parse("2027-01-01T10:00:00Z"),null,null,null,null,33L,
                null,"Customer",null,null);

        var allocations=service.allocate(1,2,3,request.startsAt(),request.startsAt().plusHours(1),
                request,member->true,false);

        assertThat(allocations).hasSize(1);
        assertThat(allocations.getFirst().category()).isEqualTo("equipment");
    }
}
