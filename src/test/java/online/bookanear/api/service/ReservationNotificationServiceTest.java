package online.bookanear.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ReservationNotificationServiceTest {
    private final NamedParameterJdbcTemplate db = mock(NamedParameterJdbcTemplate.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final ReservationNotificationService service =
            new ReservationNotificationService(db, mailSender, "noreply@example.com");

    @Test
    void emailsCustomerWhenAppointmentIsRescheduled() {
        when(db.queryForMap(anyString(), anyMap())).thenReturn(reservation(null));

        service.sendRescheduled(4, 12, OffsetDateTime.parse("2026-10-28T13:00:00Z"));

        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("customer@example.com");
        assertThat(message.getValue().getSubject()).contains("Změna termínu rezervace");
        assertThat(message.getValue().getText()).contains("28. 10. 2026 v 14:00");
    }

    @Test
    void emailsCustomerWhenReservationIsCancelled() {
        when(db.queryForMap(anyString(), anyMap())).thenReturn(
                reservation(OffsetDateTime.parse("2026-10-28T13:00:00Z")));

        service.sendCancelled(4, 12);

        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getSubject()).contains("Zrušení rezervace");
        assertThat(message.getValue().getText())
                .contains("rezervace služby Stříhání byla zrušena")
                .contains("28. 10. 2026 v 14:00");
    }

    @Test
    void doesNotAttemptEmailWhenCustomerHasNoEmailAddress() {
        when(db.queryForMap(anyString(), anyMap())).thenReturn(
                Map.of("customer_name", "Zákazník", "organization_name", "Salon",
                        "timezone", "Europe/Prague", "services", "Stříhání"));

        service.sendCancelled(4, 12);

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    private Map<String, Object> reservation(OffsetDateTime startsAt) {
        var values = new java.util.HashMap<String, Object>();
        values.put("customer_email", "customer@example.com");
        values.put("customer_name", "Zákazník");
        values.put("organization_name", "Salon");
        values.put("timezone", "Europe/Prague");
        values.put("services", "Stříhání");
        values.put("starts_at", startsAt);
        return values;
    }
}
