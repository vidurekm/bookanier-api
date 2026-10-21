package online.bookanear.api;

import online.bookanear.api.dto.EventDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL 16 integration tests; requires Docker/Testcontainers. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupEventsIT extends PostgresIntegrationTest {
    @Autowired EventService events;

    @Test
    void lastSeatCannotBeDoubleBookedConcurrently() throws Exception {
        Long org=db.queryForObject("INSERT INTO organizations(name,booking_enabled) VALUES('Yoga',true) RETURNING id",Map.of(),Long.class);
        UUID publicId=db.queryForObject("SELECT public_id FROM organizations WHERE id=:id",Map.of("id",org),UUID.class);
        db.update("INSERT INTO organization_booking_config(organization_id,template_key,booking_model,setup_status,locked_at) VALUES(:id,'EVENTS','EVENT','complete',now())",Map.of("id",org));
        Long loc=db.queryForObject("INSERT INTO locations(organization_id,name) VALUES(:id,'Studio') RETURNING id",Map.of("id",org),Long.class);
        Long series=db.queryForObject("INSERT INTO event_series(organization_id,location_id,title,capacity) VALUES(:org,:loc,'Yoga',1) RETURNING id",Map.of("org",org,"loc",loc),Long.class);
        OffsetDateTime start=OffsetDateTime.now().plusDays(2);
        Long occurrence=db.queryForObject("INSERT INTO event_occurrences(organization_id,series_id,starts_at,ends_at) VALUES(:org,:series,:start,:end) RETURNING id",Map.of("org",org,"series",series,"start",start,"end",start.plusHours(1)),Long.class);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            Callable<Boolean> attempt=()->{ready.countDown();go.await(5,TimeUnit.SECONDS);try {
                events.register(publicId,occurrence,new EventDto.Registration("Guest","guest@example.com",null,1));
                return true;
            } catch(ApiException ex) { assertThat(ex.code).isEqualTo("EVENT_FULL");return false; }};
            Future<Boolean> a=pool.submit(attempt),b=pool.submit(attempt);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat((a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            Long reserved=db.queryForObject("SELECT COALESCE(sum(seats),0) FROM event_registrations WHERE occurrence_id=:id AND status='confirmed'",Map.of("id",occurrence),Long.class);
            assertThat(reserved).isEqualTo(1L);
        } finally {pool.shutdownNow();}
    }
}
