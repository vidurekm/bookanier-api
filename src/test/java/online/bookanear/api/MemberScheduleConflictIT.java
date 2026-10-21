package online.bookanear.api;

import online.bookanear.api.dto.BackofficeDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.BackofficeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Cross-branch working-hour collision checks against PostgreSQL. */
@Testcontainers
class MemberScheduleConflictIT extends PostgresIntegrationTest {
    @Autowired BackofficeService service;

    private long id(String sql, Map<String, ?> params) {
        return ((Number) db.queryForMap(sql, params).get("id")).longValue();
    }

    private record Fixture(long org, long member, long prague, long brno) {}

    private Fixture fixture() {
        long org = id("INSERT INTO organizations(name) VALUES('Two branches') RETURNING id", Map.of());
        long member = id("""
                INSERT INTO organization_members(organization_id,display_name,role)
                VALUES(:org,'Jana','worker') RETURNING id
                """, Map.of("org", org));
        long prague = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Prague','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long brno = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Brno','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        for (long location : List.of(prague, brno)) {
            db.update("""
                    INSERT INTO member_locations(organization_id,member_id,location_id)
                    VALUES(:org,:member,:loc)
                    """, Map.of("org", org, "member", member, "loc", location));
        }
        return new Fixture(org, member, prague, brno);
    }

    private static BackofficeDto.WeeklySchedule weekly(int weekday, int start, int end) {
        return new BackofficeDto.WeeklySchedule(List.of(
                new BackofficeDto.ScheduleDay(weekday, List.of(
                        new BackofficeDto.TimeInterval(LocalTime.of(start, 0), LocalTime.of(end, 0))))));
    }

    private static BackofficeDto.DatedSchedule dated(LocalDate date, int start, int end) {
        return new BackofficeDto.DatedSchedule(List.of(
                new BackofficeDto.DatedScheduleDay(date, List.of(
                        new BackofficeDto.TimeInterval(LocalTime.of(start, 0), LocalTime.of(end, 0))))));
    }

    @Test
    void rejectsOverlappingWeeklyShiftsInDifferentBranchesAndRollsBack() {
        Fixture f = fixture();
        service.replaceWeeklySchedule(f.org(), f.prague(), f.member(), "working", weekly(3, 9, 17), false, false);
        assertThatThrownBy(() -> service.replaceWeeklySchedule(
                f.org(), f.brno(), f.member(), "working", weekly(3, 10, 18), false, false))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT));
        Integer count = db.queryForObject("""
                SELECT count(*) FROM schedule_rules
                WHERE organization_id=:org AND location_id=:loc AND kind='working'
                """, Map.of("org", f.org(), "loc", f.brno()), Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void acceptsDifferentDaysAndTouchingNonOverlappingShifts() {
        Fixture f = fixture();
        service.replaceWeeklySchedule(f.org(), f.prague(), f.member(), "working", weekly(1, 9, 17), false, false);
        service.replaceWeeklySchedule(f.org(), f.brno(), f.member(), "working", weekly(3, 10, 18), false, false);
        service.replaceWeeklySchedule(f.org(), f.brno(), f.member(), "working", weekly(1, 17, 20), false, false);
    }

    @Test
    void datedOverrideCannotOverlapAnotherBranchButClosedDayCanReleaseIt() {
        Fixture f = fixture();
        LocalDate nextWednesday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
        service.replaceWeeklySchedule(f.org(), f.prague(), f.member(), "working", weekly(3, 9, 17), false, false);
        assertThatThrownBy(() -> service.replaceDatedSchedule(
                f.org(), f.brno(), f.member(), "working", dated(nextWednesday, 10, 18), false, false))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT));
        // An explicit empty override is a day off, replacing the Prague weekly shift.
        service.replaceDatedSchedule(f.org(), f.prague(), f.member(), "working",
                new BackofficeDto.DatedSchedule(List.of(
                        new BackofficeDto.DatedScheduleDay(nextWednesday, List.of()))), false, false);
        service.replaceDatedSchedule(f.org(), f.brno(), f.member(), "working",
                dated(nextWednesday, 10, 18), false, false);
        // Deleting the Prague day off would make both branches overlap again.
        assertThatThrownBy(() -> service.deleteDatedSchedule(
                f.org(), f.prague(), f.member(), "working", nextWednesday, false, false))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void addScheduleEndpointAlsoRejectsCrossBranchCollision() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        service.addSchedule(f.org(), new BackofficeDto.ScheduleCreate(
                f.prague(), f.member(), 3, LocalTime.of(9,0), LocalTime.of(17,0), today, null));
        assertThatThrownBy(() -> service.addSchedule(f.org(), new BackofficeDto.ScheduleCreate(
                f.brno(), f.member(), 3, LocalTime.of(10,0), LocalTime.of(18,0), today, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT));
    }
}
