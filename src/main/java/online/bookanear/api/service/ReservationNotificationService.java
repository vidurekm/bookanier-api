package online.bookanear.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class ReservationNotificationService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d. M. yyyy 'v' HH:mm");

    private final NamedParameterJdbcTemplate db;
    private final JavaMailSender mailSender;
    private final String fromEmail;

    public ReservationNotificationService(NamedParameterJdbcTemplate db, JavaMailSender mailSender,
                                           @Value("${registration.from-email}") String fromEmail) {
        this.db = db;
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    public void sendRescheduled(long organizationId, long reservationId, OffsetDateTime startsAt) {
        var reservation = reservation(organizationId, reservationId);
        if (reservation.get("customer_email") == null
                || reservation.get("customer_email").toString().isBlank()) return;

        String date = startsAt.atZoneSameInstant(ZoneId.of(reservation.get("timezone").toString()))
                .format(DATE_TIME);
        send(reservation, "Změna termínu rezervace",
                "termín rezervace služby " + reservation.get("services") + " byl změněn na " + date + ".");
    }

    public void sendCancelled(long organizationId, long reservationId) {
        var reservation = reservation(organizationId, reservationId);
        if (reservation.get("customer_email") == null
                || reservation.get("customer_email").toString().isBlank()) return;

        String date = reservation.get("starts_at") == null ? "" : "\nPůvodní termín: "
                + offsetDateTime(reservation.get("starts_at"))
                .atZoneSameInstant(ZoneId.of(reservation.get("timezone").toString())).format(DATE_TIME);
        send(reservation, "Zrušení rezervace",
                "rezervace služby " + reservation.get("services") + " byla zrušena." + date);
    }

    private Map<String, Object> reservation(long organizationId, long reservationId) {
        return db.queryForMap("""
                SELECT r.customer_email,r.customer_name,o.name AS organization_name,l.timezone,
                       MIN(ri.starts_at) AS starts_at,
                       COALESCE(string_agg(DISTINCT s.name, ', ' ORDER BY s.name),'rezervace') AS services
                FROM reservations r
                JOIN organizations o ON o.id=r.organization_id
                JOIN locations l ON l.organization_id=r.organization_id AND l.id=r.location_id
                LEFT JOIN reservation_items ri ON ri.organization_id=r.organization_id AND ri.reservation_id=r.id
                LEFT JOIN services s ON s.organization_id=ri.organization_id AND s.id=ri.service_id
                WHERE r.organization_id=:org AND r.id=:id
                GROUP BY r.customer_email,r.customer_name,o.name,l.timezone
                """, Map.of("org", organizationId, "id", reservationId));
    }

    private void send(Map<String, Object> reservation, String subject, String update) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(reservation.get("customer_email").toString());
        message.setSubject(subject + " – " + reservation.get("organization_name"));
        message.setText("Dobrý den, " + reservation.get("customer_name") + ",\n\n"
                + update + "\n\nS pozdravem\n" + reservation.get("organization_name"));
        mailSender.send(message);
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported reservation timestamp type");
    }
}
