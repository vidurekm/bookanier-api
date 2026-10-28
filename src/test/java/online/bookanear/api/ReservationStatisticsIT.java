package online.bookanear.api;

import online.bookanear.api.controller.ReservationStatisticsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationStatisticsIT extends PostgresIntegrationTest {
    @Autowired ReservationStatisticsController statistics;

    @Test
    void fillsDateBucketsAndMeasuresScheduledStaffTimeInsteadOfRawResourceCount() {
        Long org = db.getJdbcTemplate().queryForObject(
                "INSERT INTO organizations(name) VALUES('Studio') RETURNING id", Long.class);
        Long location = db.getJdbcTemplate().queryForObject(
                "INSERT INTO locations(organization_id,name) VALUES(?,'Main') RETURNING id", Long.class, org);
        Long ownerUser = db.getJdbcTemplate().queryForObject("""
                INSERT INTO users(email,name,email_verified_at)
                VALUES('owner@studio.test','Owner',now()) RETURNING id
                """, Long.class);
        db.getJdbcTemplate().update("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role,provides_services)
                VALUES(?,?,'Owner','owner',false)
                """, org, ownerUser);
        Long type = db.getJdbcTemplate().queryForObject(
                "INSERT INTO resource_types(organization_id,name,category) VALUES(?,'Room','space') RETURNING id",
                Long.class, org);
        db.getJdbcTemplate().update("""
                INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,capacity)
                VALUES(?,?,'Room 1','room',?,3)
                """, org, location, type);
        Long member = db.getJdbcTemplate().queryForObject("""
                INSERT INTO organization_members(organization_id,display_name,role)
                VALUES(?,'Worker','worker') RETURNING id
                """, Long.class, org);
        Long service = db.getJdbcTemplate().queryForObject("""
                INSERT INTO services(organization_id,name) VALUES(?,'Service') RETURNING id
                """, Long.class, org);
        db.getJdbcTemplate().update("""
                INSERT INTO service_versions(service_id,valid_during,duration_minutes,
                    slot_interval_minutes,price,currency)
                VALUES(?,daterange('2026-01-01',NULL,'[)'),60,30,100,'CZK')
                """, service);
        db.getJdbcTemplate().update("""
                INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(?,?,?)
                """, org, member, location);
        db.getJdbcTemplate().update("""
                INSERT INTO worker_services(organization_id,member_id,service_id) VALUES(?,?,?)
                """, org, member, service);
        db.getJdbcTemplate().update("""
                INSERT INTO location_services(organization_id,location_id,service_id) VALUES(?,?,?)
                """, org, location, service);
        for (int weekday : new int[]{1, 2}) {
            db.getJdbcTemplate().update("""
                    INSERT INTO schedule_rules(organization_id,location_id,kind,weekday,
                        start_time,end_time,effective_dates)
                    VALUES(?,?,'open',?,'09:00','17:00',daterange('2026-01-01',NULL,'[)'))
                    """, org, location, weekday);
            db.getJdbcTemplate().update("""
                    INSERT INTO schedule_rules(organization_id,location_id,member_id,kind,
                        weekday,start_time,end_time,effective_dates)
                    VALUES(?,?,?,'working',?,'09:00','17:00',daterange('2026-01-01',NULL,'[)'))
                    """, org, location, member, weekday);
        }

        for (int hour = 9; hour < 16; hour++) {
            OffsetDateTime start = LocalDate.of(2026, 1, 5).atTime(hour, 0)
                    .atZone(ZoneId.of("Europe/Prague")).toOffsetDateTime();
            OffsetDateTime end = start.plusHours(1);
            Long reservation = db.getJdbcTemplate().queryForObject("""
                    INSERT INTO reservations(organization_id,location_id,customer_name,
                        status,currency,total_amount)
                    VALUES(?,?,'Client','completed','CZK',100) RETURNING id
                    """, Long.class, org, location);
            db.getJdbcTemplate().update("""
                    INSERT INTO reservation_items(organization_id,location_id,reservation_id,
                        service_id,service_version_id,member_id,starts_at,ends_at,occupied_period,
                        duration_minutes,price_amount,currency,item_status)
                    SELECT ?,?,?,?,sv.id,?,?,?,tstzrange(CAST(? AS timestamptz),
                        CAST(? AS timestamptz),'[)'),60,100,'CZK','active'
                    FROM service_versions sv WHERE sv.service_id=?
                    """, org, location, reservation, service, member, start, end,
                    start, end, service);
        }

        LocalDate from = LocalDate.of(2026, 1, 5);
        var jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(ownerUser.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var points = statistics.statistics(org, from, from.plusDays(1), "day", location,
                new JwtAuthenticationToken(jwt));

        assertThat(points).hasSize(2);
        assertThat(points).extracting(point -> point.get("period_start"))
                .containsExactly(from, from.plusDays(1));
        assertThat(((Number) points.getFirst().get("capacity")).doubleValue()).isEqualTo(480);
        assertThat(((Number) points.getFirst().get("used_capacity")).doubleValue()).isEqualTo(420);
        assertThat(((Number) points.getFirst().get("reservation_count")).longValue()).isEqualTo(7);
        assertThat(((Number) points.get(1).get("capacity")).doubleValue()).isEqualTo(480);
        assertThat(((Number) points.get(1).get("used_capacity")).doubleValue()).isZero();
        assertThat(((Number) points.get(1).get("reservation_count")).longValue()).isZero();
        assertThat(points.get(1).get("revenue")).isEqualTo(java.util.List.of());
    }

    @Test
    void validatesGroupingAndDateRange() {
        Long org = db.getJdbcTemplate().queryForObject(
                "INSERT INTO organizations(name) VALUES('Studio') RETURNING id", Long.class);
        assertThatThrownBy(() ->
                statistics.statistics(org, LocalDate.now(), LocalDate.now(), "hour", null, null))
                .isInstanceOf(online.bookanear.api.exception.ApiException.class);
        assertThatThrownBy(() ->
                statistics.statistics(org, LocalDate.now(), LocalDate.now().plusDays(366), "day", null, null))
                .isInstanceOf(online.bookanear.api.exception.ApiException.class);
    }

    @Test
    void countsScheduledWorkForEveryWorkerAssignedToOneReservation() {
        Long org = db.getJdbcTemplate().queryForObject(
                "INSERT INTO organizations(name) VALUES('Studio') RETURNING id", Long.class);
        Long location = db.getJdbcTemplate().queryForObject(
                "INSERT INTO locations(organization_id,name) VALUES(?,'Main') RETURNING id", Long.class, org);
        Long ownerUser = db.getJdbcTemplate().queryForObject("""
                INSERT INTO users(email,name,email_verified_at)
                VALUES('owner@multi.test','Owner',now()) RETURNING id
                """, Long.class);
        db.getJdbcTemplate().update("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role,provides_services)
                VALUES(?,?,'Owner','owner',false)
                """, org, ownerUser);
        Long service = db.getJdbcTemplate().queryForObject(
                "INSERT INTO services(organization_id,name) VALUES(?,'Service') RETURNING id",
                Long.class, org);
        Long version = db.getJdbcTemplate().queryForObject("""
                INSERT INTO service_versions(service_id,valid_during,duration_minutes,
                    slot_interval_minutes,price,currency)
                VALUES(?,daterange('2026-01-01',NULL,'[)'),60,30,100,'CZK') RETURNING id
                """, Long.class, service);
        Long staffType = db.getJdbcTemplate().queryForObject("""
                INSERT INTO resource_types(organization_id,name,category,code)
                VALUES(?,'Staff','staff','staff') RETURNING id
                """, Long.class, org);
        db.getJdbcTemplate().update("""
                INSERT INTO service_resource_requirements(service_id,resource_type,resource_type_id,quantity)
                VALUES(?,'staff',?,2)
                """, service, staffType);
        db.getJdbcTemplate().update("""
                INSERT INTO location_services(organization_id,location_id,service_id) VALUES(?,?,?)
                """, org, location, service);
        Long firstMember = insertWorker(org, location, service, staffType, "First");
        Long secondMember = insertWorker(org, location, service, staffType, "Second");
        db.getJdbcTemplate().update("""
                INSERT INTO schedule_rules(organization_id,location_id,kind,weekday,
                    start_time,end_time,effective_dates)
                VALUES(?,?,'open',1,'09:00','10:00',daterange('2026-01-01',NULL,'[)'))
                """, org, location);
        for (Long member : List.of(firstMember, secondMember)) {
            db.getJdbcTemplate().update("""
                    INSERT INTO schedule_rules(organization_id,location_id,member_id,kind,
                        weekday,start_time,end_time,effective_dates)
                    VALUES(?,?,?,'working',1,'09:00','10:00',daterange('2026-01-01',NULL,'[)'))
                    """, org, location, member);
        }

        OffsetDateTime start = LocalDate.of(2026, 1, 5).atTime(9, 0)
                .atZone(ZoneId.of("Europe/Prague")).toOffsetDateTime();
        OffsetDateTime end = start.plusHours(1);
        Long reservation = db.getJdbcTemplate().queryForObject("""
                INSERT INTO reservations(organization_id,location_id,customer_name,
                    status,currency,total_amount)
                VALUES(?,?,'Client','completed','CZK',100) RETURNING id
                """, Long.class, org, location);
        Long item = db.getJdbcTemplate().queryForObject("""
                INSERT INTO reservation_items(organization_id,location_id,reservation_id,
                    service_id,service_version_id,member_id,starts_at,ends_at,occupied_period,
                    duration_minutes,price_amount,currency)
                VALUES(?,?,?,?,?,?,?,?,tstzrange(CAST(? AS timestamptz),
                    CAST(? AS timestamptz),'[)'),60,100,'CZK') RETURNING id
                """, Long.class, org, location, reservation, service, version, firstMember,
                start, end, start, end);
        for (Long member : List.of(firstMember, secondMember)) {
            Long resource = db.getJdbcTemplate().queryForObject("""
                    SELECT id FROM resources WHERE organization_id=? AND location_id=? AND member_id=?
                    """, Long.class, org, location, member);
            db.getJdbcTemplate().update("""
                    INSERT INTO reservation_resources(organization_id,location_id,reservation_item_id,
                        resource_id,occupied_period)
                    VALUES(?,?,?, ?,tstzrange(CAST(? AS timestamptz),CAST(? AS timestamptz),'[)'))
                    """, org, location, item, resource, start, end);
        }

        var jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(ownerUser.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var points = statistics.statistics(org, LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 5), "day", location, new JwtAuthenticationToken(jwt));

        assertThat(((Number) points.getFirst().get("capacity")).doubleValue()).isEqualTo(120);
        assertThat(((Number) points.getFirst().get("used_capacity")).doubleValue()).isEqualTo(120);
    }

    private Long insertWorker(Long org, Long location, Long service, Long staffType, String name) {
        Long member = db.getJdbcTemplate().queryForObject("""
                INSERT INTO organization_members(organization_id,display_name,role,provides_services)
                VALUES(?,?,'worker',true) RETURNING id
                """, Long.class, org, name);
        db.getJdbcTemplate().update("""
                INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(?,?,?)
                """, org, member, location);
        db.getJdbcTemplate().update("""
                INSERT INTO worker_services(organization_id,member_id,service_id) VALUES(?,?,?)
                """, org, member, service);
        db.getJdbcTemplate().update("""
                INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,
                    capacity,member_id)
                VALUES(?,?,?,'staff',?,1,?)
                """, org, location, name, staffType, member);
        return member;
    }
}
