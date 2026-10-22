package online.bookanear.api;

import online.bookanear.api.exception.ApiException;
import online.bookanear.api.dto.BackofficeDto;
import online.bookanear.api.service.BackofficeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class BackofficeArchiveIT extends PostgresIntegrationTest {
    @Autowired BackofficeService service;

    private long id(String sql, Map<String, ?> params) {
        return ((Number) db.queryForMap(sql, params).get("id")).longValue();
    }

    private record BookingFixture(long org, long location, long member, long reservation) {}

    private BookingFixture futureBooking() {
        long org = id("INSERT INTO organizations(name) VALUES('Archive test') RETURNING id", Map.of());
        long location = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Booked branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long member = id("""
                INSERT INTO organization_members(organization_id,display_name,role)
                VALUES(:org,'Worker','worker') RETURNING id
                """, Map.of("org", org));
        long serviceId = id("""
                INSERT INTO services(organization_id,name) VALUES(:org,'Service') RETURNING id
                """, Map.of("org", org));
        long versionId = id("""
                INSERT INTO service_versions(service_id,valid_during,duration_minutes,
                    slot_interval_minutes,price,currency)
                VALUES(:service,daterange(current_date,NULL,'[)'),60,15,0,'CZK') RETURNING id
                """, Map.of("service", serviceId));
        db.update("INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(:org,:member,:location)",
                Map.of("org", org, "member", member, "location", location));
        db.update("INSERT INTO worker_services(organization_id,member_id,service_id) VALUES(:org,:member,:service)",
                Map.of("org", org, "member", member, "service", serviceId));
        db.update("INSERT INTO location_services(organization_id,location_id,service_id) VALUES(:org,:location,:service)",
                Map.of("org", org, "location", location, "service", serviceId));
        long reservation = id("""
                INSERT INTO reservations(organization_id,location_id,customer_name,status,total_amount)
                VALUES(:org,:location,'Customer','confirmed',0) RETURNING id
                """, Map.of("org", org, "location", location));
        OffsetDateTime starts = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime ends = starts.plusMinutes(60);
        db.update("""
                INSERT INTO reservation_items(organization_id,location_id,reservation_id,service_id,
                    service_version_id,member_id,starts_at,ends_at,occupied_period,duration_minutes,
                    price_amount,currency)
                VALUES(:org,:location,:reservation,:service,:version,:member,:starts,:ends,
                    tstzrange(:starts,:ends,'[)'),60,0,'CZK')
                """, Map.of("org", org, "location", location, "reservation", reservation,
                "service", serviceId, "version", versionId, "member", member, "starts", starts, "ends", ends));
        return new BookingFixture(org, location, member, reservation);
    }

    @Test
    void archivesLocationAndRetainsItsDatabaseRecord() {
        long org = id("INSERT INTO organizations(name) VALUES('Archive test') RETURNING id", Map.of());
        long location = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Old branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));

        service.archiveLocation(org, location);

        Map<String, Object> archived = db.queryForMap(
                "SELECT active,archived_at FROM locations WHERE organization_id=:org AND id=:id",
                Map.of("org", org, "id", location));
        assertThat(archived.get("active")).isEqualTo(false);
        assertThat(archived.get("archived_at")).isNotNull();
        assertThatThrownBy(() -> service.archiveLocation(org, location))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void removesWorkerMembershipButKeepsTheirUserAccount() {
        long org = id("INSERT INTO organizations(name) VALUES('Archive test') RETURNING id", Map.of());
        long user = id("INSERT INTO users(email,name) VALUES('worker@example.test','Worker') RETURNING id", Map.of());
        long member = id("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role)
                VALUES(:org,:user,'Worker','worker') RETURNING id
                """, Map.of("org", org, "user", user));

        service.archiveMember(org, member);

        Map<String, Object> archived = db.queryForMap("""
                SELECT active,archived_at,user_id FROM organization_members
                WHERE organization_id=:org AND id=:id
                """, Map.of("org", org, "id", member));
        assertThat(archived.get("active")).isEqualTo(false);
        assertThat(archived.get("archived_at")).isNotNull();
        assertThat(archived.get("user_id")).isEqualTo(user);
        assertThat(db.queryForObject("SELECT count(*) FROM users WHERE id=:id",
                Map.of("id", user), Integer.class)).isEqualTo(1);
    }

    @Test
    void requiresFutureReservationsToBeCancelledBeforeArchiving() {
        BookingFixture booking = futureBooking();

        assertThatThrownBy(() -> service.archiveLocation(booking.org(), booking.location()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code).isEqualTo("LOCATION_HAS_ACTIVE_RESERVATIONS"));
        assertThatThrownBy(() -> service.archiveMember(booking.org(), booking.member()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code).isEqualTo("MEMBER_HAS_ACTIVE_RESERVATIONS"));

        db.update("UPDATE reservations SET status='cancelled' WHERE id=:id",
                Map.of("id", booking.reservation()));
        service.archiveLocation(booking.org(), booking.location());
        service.archiveMember(booking.org(), booking.member());
    }

    @Test
    void deletesUnusedServiceAndItsConfiguration() {
        long org = id("INSERT INTO organizations(name) VALUES('Service deletion test') RETURNING id", Map.of());
        long location = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long member = id("""
                INSERT INTO organization_members(organization_id,display_name,role)
                VALUES(:org,'Worker','worker') RETURNING id
                """, Map.of("org", org));
        long serviceId = id("INSERT INTO services(organization_id,name) VALUES(:org,'Unused service') RETURNING id",
                Map.of("org", org));
        db.update("""
                INSERT INTO service_versions(service_id,valid_during,duration_minutes,slot_interval_minutes,price)
                VALUES(:service,daterange(current_date,NULL,'[)'),30,15,0)
                """, Map.of("service", serviceId));
        db.update("INSERT INTO worker_services(organization_id,member_id,service_id) VALUES(:org,:member,:service)",
                Map.of("org", org, "member", member, "service", serviceId));
        db.update("INSERT INTO location_services(organization_id,location_id,service_id) VALUES(:org,:location,:service)",
                Map.of("org", org, "location", location, "service", serviceId));

        service.deleteService(org, serviceId);

        assertThat(db.queryForObject("SELECT count(*) FROM services WHERE id=:id",
                Map.of("id", serviceId), Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM service_versions WHERE service_id=:id",
                Map.of("id", serviceId), Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM worker_services WHERE service_id=:id",
                Map.of("id", serviceId), Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM location_services WHERE service_id=:id",
                Map.of("id", serviceId), Integer.class)).isZero();
    }

    @Test
    void refusesToDeleteServiceUsedByAReservation() {
        BookingFixture booking = futureBooking();
        long serviceId = db.queryForObject(
                "SELECT service_id FROM reservation_items WHERE reservation_id=:id",
                Map.of("id", booking.reservation()), Long.class);

        assertThatThrownBy(() -> service.deleteService(booking.org(), serviceId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code).isEqualTo("SERVICE_IN_USE");
                });
        assertThat(db.queryForObject("SELECT count(*) FROM services WHERE id=:id",
                Map.of("id", serviceId), Integer.class)).isEqualTo(1);
    }

    @Test
    void refusesToDeleteServiceUsedByWaitlist() {
        long org = id("INSERT INTO organizations(name) VALUES('Waitlist service test') RETURNING id", Map.of());
        long location = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long serviceId = id("INSERT INTO services(organization_id,name) VALUES(:org,'Waitlisted service') RETURNING id",
                Map.of("org", org));
        db.update("""
                INSERT INTO waitlist_entries(organization_id,location_id,service_id,preferred_from,preferred_to)
                VALUES(:org,:location,:service,now(),now()+interval '1 hour')
                """, Map.of("org", org, "location", location, "service", serviceId));

        assertThatThrownBy(() -> service.deleteService(org, serviceId))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code).isEqualTo("SERVICE_IN_USE"));
        assertThat(db.queryForObject("SELECT count(*) FROM services WHERE id=:id",
                Map.of("id", serviceId), Integer.class)).isEqualTo(1);
    }

    @Test
    void createsCustomerWithInternalNote() {
        long org = id("INSERT INTO organizations(name) VALUES('Customer note test') RETURNING id", Map.of());

        service.createCustomer(org, new BackofficeDto.CreateCustomer("Jane", null, null, "Afternoon appointments"));

        assertThat(db.queryForObject("SELECT notes FROM customers WHERE organization_id=:org",
                Map.of("org", org), String.class)).isEqualTo("Afternoon appointments");
    }

    @Test
    void deletesCustomerAndPreservesReservationHistory() {
        long org = id("INSERT INTO organizations(name) VALUES('Customer deletion test') RETURNING id", Map.of());
        long customer = id("""
                INSERT INTO customers(organization_id,name,email)
                VALUES(:org,'Jane','jane@example.test') RETURNING id
                """, Map.of("org", org));
        long location = id("""
                INSERT INTO locations(organization_id,name,timezone)
                VALUES(:org,'Branch','Europe/Prague') RETURNING id
                """, Map.of("org", org));
        long serviceId = id("INSERT INTO services(organization_id,name) VALUES(:org,'Service') RETURNING id",
                Map.of("org", org));
        long reservation = id("""
                INSERT INTO reservations(organization_id,location_id,customer_id,customer_name,
                    customer_email,status,total_amount)
                VALUES(:org,:location,:customer,'Jane','jane@example.test','confirmed',0) RETURNING id
                """, Map.of("org", org, "location", location, "customer", customer));
        db.update("""
                INSERT INTO waitlist_entries(organization_id,location_id,customer_id,service_id,
                    preferred_from,preferred_to)
                VALUES(:org,:location,:customer,:service,now(),now()+interval '1 hour')
                """, Map.of("org", org, "location", location, "customer", customer, "service", serviceId));

        service.deleteCustomer(org, customer);

        assertThat(db.queryForObject("SELECT count(*) FROM customers WHERE organization_id=:org AND id=:id",
                Map.of("org", org, "id", customer), Integer.class)).isZero();
        Map<String, Object> booking = db.queryForMap(
                "SELECT customer_id,customer_name,customer_email FROM reservations WHERE id=:id",
                Map.of("id", reservation));
        assertThat(booking.get("customer_id")).isNull();
        assertThat(booking.get("customer_name")).isEqualTo("Jane");
        assertThat(booking.get("customer_email")).isEqualTo("jane@example.test");
        Map<String, Object> waitlist = db.queryForMap(
                "SELECT customer_id,status FROM waitlist_entries WHERE customer_id IS NULL AND organization_id=:org",
                Map.of("org", org));
        assertThat(waitlist.get("customer_id")).isNull();
        assertThat(waitlist.get("status")).isEqualTo("cancelled");
        assertThatThrownBy(() -> service.deleteCustomer(org, customer))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
