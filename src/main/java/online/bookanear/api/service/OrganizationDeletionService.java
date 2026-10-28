package online.bookanear.api.service;

import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class OrganizationDeletionService {
    private final NamedParameterJdbcTemplate db;
    private final ObjectProvider<MediaService> mediaService;

    public OrganizationDeletionService(NamedParameterJdbcTemplate db, ObjectProvider<MediaService> mediaService) {
        this.db = db;
        this.mediaService = mediaService;
    }

    @Transactional
    public void delete(long organizationId) {
        Integer exists = db.queryForObject("SELECT count(*) FROM organizations WHERE id=:org",
                Map.of("org", organizationId), Integer.class);
        if (exists == null || exists == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");

        MediaService media = mediaService.getIfAvailable();
        Integer mediaCount = db.queryForObject("SELECT count(*) FROM media_assets WHERE organization_id=:org",
                Map.of("org", organizationId), Integer.class);
        if (mediaCount != null && mediaCount > 0 && media == null) {
            throw new IllegalStateException("Media storage is unavailable; organization data was not deleted");
        }
        if (media != null) media.deleteOrganizationAssets(organizationId);
        deleteRows("DELETE FROM group_session_attendees WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM group_sessions WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM event_registrations WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM event_occurrences WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM event_series WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM reservation_item_resource_demands WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM reservation_resources WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM reservation_items WHERE organization_id=:org", organizationId);
        deleteRows("""
                DELETE FROM payment_refunds WHERE payment_id IN (
                    SELECT p.id FROM payments p JOIN reservations r ON r.id=p.reservation_id
                    WHERE r.organization_id=:org
                )
                """, organizationId);
        deleteRows("DELETE FROM payments WHERE reservation_id IN (SELECT id FROM reservations WHERE organization_id=:org)", organizationId);
        deleteRows("DELETE FROM reservation_tokens WHERE reservation_id IN (SELECT id FROM reservations WHERE organization_id=:org)", organizationId);
        deleteRows("DELETE FROM reservation_events WHERE reservation_id IN (SELECT id FROM reservations WHERE organization_id=:org)", organizationId);
        deleteRows("DELETE FROM notification_outbox WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM reservations WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM waitlist_entries WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM member_absence_requests WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_invitations WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM schedule_date_overrides WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM schedule_rules WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM availability_overrides WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM booking_policies WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_logos WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM location_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM service_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM resource_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM member_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM resource_type_images WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM media_assets WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM service_resource_requirements WHERE service_id IN (SELECT id FROM services WHERE organization_id=:org)", organizationId);
        deleteRows("DELETE FROM service_price_overrides WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM worker_services WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM location_services WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM member_locations WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_registration_tokens WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_booking_config WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM resources WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM service_versions WHERE service_id IN (SELECT id FROM services WHERE organization_id=:org)", organizationId);
        deleteRows("DELETE FROM services WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM customers WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organization_members WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM locations WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM resource_types WHERE organization_id=:org", organizationId);
        deleteRows("DELETE FROM organizations WHERE id=:org", organizationId);
    }

    private void deleteRows(String sql, long organizationId) {
        db.update(sql, Map.of("org", organizationId));
    }
}
