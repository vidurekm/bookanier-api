package online.bookanear.api.service;

import online.bookanear.api.dto.BackofficeDto;
import online.bookanear.api.exception.ApiException;
import online.bookanear.api.exception.ScheduleConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import online.bookanear.api.config.OrganizationSecurity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.Map;
import java.util.List;

/**
 * Backoffice API. Every mapped endpoint has tenant-aware method authorization.
 */
import org.springframework.stereotype.Service;

@Service
public class BackofficeService {
    private final NamedParameterJdbcTemplate db;
    private final OrganizationSecurity organizationSecurity;
    private final MemberScheduleConflictValidator memberScheduleConflictValidator;
    private final OrganizationDeletionService organizationDeletionService;
    private final StaffResourceService staffResourceService;
    public BackofficeService(NamedParameterJdbcTemplate db, OrganizationSecurity organizationSecurity,
                             MemberScheduleConflictValidator memberScheduleConflictValidator,
                             OrganizationDeletionService organizationDeletionService,
                             StaffResourceService staffResourceService) {
        this.db=db; this.organizationSecurity=organizationSecurity;
        this.memberScheduleConflictValidator=memberScheduleConflictValidator;
        this.organizationDeletionService=organizationDeletionService;
        this.staffResourceService=staffResourceService;
    }
    private Map<String, Object> one(String sql, Map<String, ?> params) {
        var result = db.queryForList(sql, params);
        if (result.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Record not found");
        return result.get(0);
    }

    private Map<String, Object> p(long org, long id) {
        return Map.of("org", org, "id", id);
    }

    @Transactional
    public Map<String, Object> createOrganization(BackofficeDto.OrganizationCreate request,
                                                  Authentication authentication) {
        Long userId = organizationSecurity.authenticatedUserId(authentication);
        if (userId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");

        var users = db.queryForList("""
                SELECT name, email FROM users
                WHERE id=:user AND email_verified_at IS NOT NULL
                """, Map.of("user", userId));
        if (users.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");

        var user = users.getFirst();
        Long organizationId = db.queryForObject("""
                INSERT INTO organizations(name,email,booking_enabled,registration_status,verified_at)
                VALUES (:name,:email,true,'active',now()) RETURNING id
                """, new MapSqlParameterSource()
                .addValue("name", request.name().trim())
                .addValue("email", user.get("email")), Long.class);
        if (organizationId == null) throw new IllegalStateException("Organization insert did not return an id");

        db.update("INSERT INTO organization_booking_config(organization_id) VALUES(:org)",
                Map.of("org", organizationId));
        db.update("""
                INSERT INTO organization_members(organization_id,user_id,display_name,role)
                VALUES (:org,:user,:name,'owner')
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("user", userId)
                .addValue("name", user.get("name")));
        return Map.of("id", organizationId, "name", request.name().trim());
    }

    public Object service(long org, long id) {
        return one("select * from services where organization_id=:org and id=:id", p(org, id));
    }

    @Transactional
    public Object updateService(long org, long id, BackofficeDto.ServiceUpsert r) {
        var args = new MapSqlParameterSource().addValue("org", org).addValue("id", id).addValue("name", r.name()).addValue("description", r.description()).addValue("active", r.active());
        return one("update services set name=:name,description=:description,active=:active where organization_id=:org and id=:id returning *", args.getValues());
    }

    @Transactional
    public void deleteService(long org, long id) {
        var service = db.queryForList(
                "SELECT id FROM services WHERE organization_id=:org AND id=:id FOR UPDATE", p(org, id));
        if (service.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Service not found");
        boolean used = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM reservation_items WHERE organization_id=:org AND service_id=:id)
                    OR EXISTS(SELECT 1 FROM waitlist_entries WHERE organization_id=:org AND service_id=:id)
                """, p(org, id), Boolean.class));
        if (used) throw new ApiException(HttpStatus.CONFLICT, "SERVICE_IN_USE",
                "A service used by reservations or waitlist entries cannot be deleted");

        db.update("DELETE FROM service_images WHERE organization_id=:org AND service_id=:id", p(org, id));
        db.update("DELETE FROM service_resource_requirements WHERE service_id=:id", p(org, id));
        db.update("DELETE FROM worker_services WHERE organization_id=:org AND service_id=:id", p(org, id));
        db.update("DELETE FROM location_services WHERE organization_id=:org AND service_id=:id", p(org, id));
        db.update("DELETE FROM service_versions WHERE service_id=:id", p(org, id));
        if (db.update("DELETE FROM services WHERE organization_id=:org AND id=:id", p(org, id)) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "Service not found");
    }

    public Object versions(long org, long id) {
        service(org, id);
        return db.queryForList("select sv.id,sv.service_id,lower(sv.valid_during) valid_from,upper(sv.valid_during) valid_to,sv.duration_minutes,sv.slot_interval_minutes,sv.buffer_before_minutes,sv.buffer_after_minutes,sv.price,sv.currency,sv.duration_mode,sv.max_duration_minutes from service_versions sv join services s on s.id=sv.service_id where s.organization_id=:org and s.id=:id order by lower(sv.valid_during)", p(org, id));
    }

    @Transactional
    public Object createVersion(long org, long id, BackofficeDto.ServiceVersionCreate r) {
        service(org, id);
        var args = new MapSqlParameterSource().addValue("id", id).addValue("from", r.validFrom()).addValue("to", r.validTo()).addValue("duration", r.durationMinutes()).addValue("slot", r.slotIntervalMinutes()).addValue("before", r.bufferBeforeMinutes() == null ? 0 : r.bufferBeforeMinutes()).addValue("after", r.bufferAfterMinutes() == null ? 0 : r.bufferAfterMinutes()).addValue("price", r.price()).addValue("currency", r.currency()).addValue("mode", r.durationMode() == null ? "FIXED" : r.durationMode()).addValue("max", r.maxDurationMinutes());
        return one("insert into service_versions(service_id,valid_during,duration_minutes,slot_interval_minutes,buffer_before_minutes,buffer_after_minutes,price,currency,duration_mode,max_duration_minutes) values(:id,daterange(:from,:to,'[)'),:duration,:slot,:before,:after,:price,:currency,:mode,:max) returning *", args.getValues());
    }

    public Object resourceTypes(long org) {
        return db.queryForList("SELECT * FROM resource_types WHERE organization_id=:org ORDER BY name",
                Map.of("org", org));
    }

    @Transactional
    public Object createResourceType(long org, BackofficeDto.ResourceTypeCreate r) {
        if (appointmentModel(org) && !"equipment".equals(r.category()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Appointment services support equipment resource types only");
        return one("""
                INSERT INTO resource_types(organization_id,name,category)
                VALUES(:org,:name,:category) RETURNING *
                """, Map.of("org", org, "name", r.name(), "category", r.category()));
    }

    @Transactional
    public Object updateResourceType(long org, long id, BackofficeDto.ResourceTypeUpdate r) {
        var existing = one("SELECT category FROM resource_types WHERE organization_id=:org AND id=:id",p(org,id));
        if (!existing.get("category").equals(r.category())) {
            if (appointmentModel(org) && !"equipment".equals(r.category()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Appointment services support equipment resource types only");
            var used = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM resources WHERE organization_id=:org AND resource_type_id=:id)
                  OR EXISTS(SELECT 1 FROM service_resource_requirements WHERE resource_type_id=:id)
                """,p(org,id),Boolean.class);
            if (Boolean.TRUE.equals(used))
                throw new ApiException(HttpStatus.CONFLICT,"Resource category cannot change while used by services or resources");
        }
        return one("""
                UPDATE resource_types SET name=:name,category=:category,active=:active
                WHERE organization_id=:org AND id=:id RETURNING *
                """, Map.of("org", org, "id", id, "name", r.name(),
                        "category", r.category(), "active", r.active()));
    }

    private boolean appointmentModel(long org) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM organization_booking_config
                    WHERE organization_id=:org AND booking_model='APPOINTMENT'
                )
                """, Map.of("org", org), Boolean.class));
    }

    @Transactional
    public void deleteResourceType(long org, long id) {
        var existing = db.queryForList("""
                SELECT category FROM resource_types
                WHERE organization_id=:org AND id=:id
                FOR UPDATE
                """, p(org, id));
        if (existing.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Resource type not found");
        if ("staff".equals(existing.get(0).get("category")))
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_TYPE_IN_USE",
                    "Staff resource types are managed by the team");

        boolean used = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM resources WHERE organization_id=:org AND resource_type_id=:id)
                    OR EXISTS(SELECT 1 FROM service_resource_requirements WHERE resource_type_id=:id)
                """, p(org, id), Boolean.class));
        if (used) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_TYPE_IN_USE",
                "A resource type used by resources or services cannot be deleted");

        db.update("DELETE FROM resource_type_images WHERE organization_id=:org AND resource_type_id=:id",
                p(org, id));
        if (db.update("DELETE FROM resource_types WHERE organization_id=:org AND id=:id", p(org, id)) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "Resource type not found");
    }

    public Object resources(long org, long loc) {
        location(org, loc);
        return db.queryForList("""
                SELECT r.id,r.organization_id,r.location_id,r.name,r.resource_type_id,
                       rt.code AS resource_type,rt.name AS resource_type_name,rt.category,r.capacity,r.member_id,
                       r.inventory_code,r.resource_state,r.active
                FROM resources r JOIN resource_types rt ON rt.id=r.resource_type_id
                WHERE r.organization_id=:org AND r.location_id=:loc ORDER BY r.id
                """, Map.of("org", org, "loc", loc));
    }

    @Transactional
    public Object createResource(long org, BackofficeDto.ResourceCreate r) {
        location(org, r.locationId());
        var type = one("SELECT * FROM resource_types WHERE organization_id=:org AND id=:id AND active",
                p(org, r.resourceTypeId()));
        boolean staff = "staff".equals(type.get("category"));
        if (staff != (r.memberId() != null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Staff resources require memberId; other types must not have it");
        if (r.memberId() != null) {
            var assignment = db.queryForList("""
                    SELECT 1 FROM organization_members m JOIN member_locations ml
                      ON ml.organization_id=m.organization_id AND ml.member_id=m.id
                    WHERE m.organization_id=:org AND m.id=:member AND ml.location_id=:loc AND m.active
                    """, Map.of("org", org, "member", r.memberId(), "loc", r.locationId()));
            if (assignment.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Member is not assigned to this location");
            if (r.capacity() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "Staff capacity must be 1");
        }
        return one("""
                INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,
                                      capacity,member_id,inventory_code)
                VALUES(:org,:loc,:name,:typeName,:type,:capacity,:member,:inventory)
                RETURNING *
                """, new MapSqlParameterSource().addValue("org", org).addValue("loc", r.locationId())
                .addValue("name", r.name()).addValue("typeName", type.get("code"))
                .addValue("type", r.resourceTypeId()).addValue("capacity", r.capacity())
                .addValue("member", r.memberId()).addValue("inventory", r.inventoryCode()).getValues());
    }

    @Transactional
    public Object updateResource(long org, long loc, long id, BackofficeDto.ResourceUpdate r) {
        var current = one("SELECT r.*,rt.category FROM resources r JOIN resource_types rt ON rt.id=r.resource_type_id WHERE r.organization_id=:org AND r.location_id=:loc AND r.id=:id",
                Map.of("org", org, "loc", loc, "id", id));
        if ("staff".equals(current.get("category")) && r.capacity() != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Staff capacity must be 1");
        return one("""
                UPDATE resources SET name=:name,capacity=:capacity,active=:active,
                    resource_state=:state
                WHERE organization_id=:org AND location_id=:loc AND id=:id RETURNING *
                """, new MapSqlParameterSource().addValue("org", org).addValue("loc", loc)
                .addValue("id", id).addValue("name", r.name()).addValue("capacity", r.capacity())
                .addValue("active", r.active()).addValue("state", r.resourceState()).getValues());
    }

    @Transactional
    public void deleteResource(long org, long loc, long id) {
        var current = db.queryForList("""
                SELECT r.id, r.member_id, rt.category
                FROM resources r
                JOIN resource_types rt ON rt.id=r.resource_type_id
                WHERE r.organization_id=:org AND r.location_id=:loc AND r.id=:id
                FOR UPDATE OF r
                """, Map.of("org", org, "loc", loc, "id", id));
        if (current.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Resource not found");
        var resource = current.get(0);
        boolean reserved = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM reservation_resources
                              WHERE organization_id=:org AND location_id=:loc AND resource_id=:id)
                    OR EXISTS(SELECT 1 FROM reservation_item_resource_demands
                              WHERE organization_id=:org AND location_id=:loc AND preferred_resource_id=:id)
                """, Map.of("org", org, "loc", loc, "id", id), Boolean.class));
        if (reserved || resource.get("member_id") != null)
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_IN_USE",
                    "A resource used in reservations or managed by a team member cannot be deleted");

        db.update("DELETE FROM resource_images WHERE organization_id=:org AND resource_id=:id",
                Map.of("org", org, "id", id));
        if (db.update("DELETE FROM resources WHERE organization_id=:org AND location_id=:loc AND id=:id",
                Map.of("org", org, "loc", loc, "id", id)) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "Resource not found");
    }

    public Object resourceRequirements(long org, long serviceId) {
        service(org, serviceId);
        return db.queryForList("""
                SELECT req.resource_type_id,rt.code AS resource_type,rt.name AS resource_type_name,rt.category,
                       req.quantity,req.min_capacity
                FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id=req.resource_type_id AND rt.organization_id=s.organization_id
                WHERE req.service_id=:service AND rt.organization_id=:org
                ORDER BY rt.name
                """, Map.of("org", org, "service", serviceId));
    }

    public Object serviceBookingOptions(long org, long serviceId) {
        service(org, serviceId);
        return one("""
                SELECT s.customer_can_choose_staff,s.customer_can_choose_resource,
                       COALESCE((SELECT req.quantity FROM service_resource_requirements req
                           JOIN resource_types rt ON rt.id=req.resource_type_id
                           WHERE req.service_id=s.id AND rt.category='staff'),1) AS required_staff_count
                FROM services s WHERE s.organization_id=:org AND s.id=:id
                """, p(org, serviceId));
    }

    @Transactional
    public Object updateServiceBookingOptions(long org, long serviceId,
                                              BackofficeDto.ServiceBookingOptions request) {
        service(org, serviceId);
        Long staffRequirements = db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id=req.resource_type_id
                WHERE req.service_id=:id AND rt.organization_id=:org AND rt.category='staff' AND rt.active
                """, p(org, serviceId), Long.class);
        Long resourceRequirements = db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id=req.resource_type_id
                WHERE req.service_id=:id AND rt.organization_id=:org AND rt.category<>'staff' AND rt.active
                """, p(org, serviceId), Long.class);
        Long singleUnitRequirements = db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id=req.resource_type_id
                WHERE req.service_id=:id AND rt.organization_id=:org
                  AND rt.category<>'staff' AND rt.active AND req.quantity=1
                """, p(org, serviceId), Long.class);
        if (staffRequirements != null && staffRequirements > 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Multiple staff requirement types are not supported");
        if (Boolean.TRUE.equals(request.customerCanChooseResource())
                && (resourceRequirements == null || resourceRequirements != 1
                    || singleUnitRequirements == null || singleUnitRequirements != 1))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Customer resource choice requires one non-staff resource of quantity one");
        if ((staffRequirements == null || staffRequirements == 0) && request.requiredStaffCount() != 1)
            if (appointmentModel(org)) {
                Long staffType=db.queryForObject("""
                        SELECT id FROM resource_types WHERE organization_id=:org
                          AND category='staff' AND active ORDER BY id LIMIT 1
                        """,Map.of("org",org),Long.class);
                if (staffType==null)
                    throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                            "Add bookable team members before requiring multiple staff members");
                db.update("""
                        INSERT INTO service_resource_requirements
                            (service_id,resource_type,resource_type_id,quantity,min_capacity)
                        SELECT :id,rt.code,rt.id,:quantity,NULL FROM resource_types rt
                        WHERE rt.organization_id=:org AND rt.id=:type
                        """,new MapSqlParameterSource().addValue("org",org).addValue("id",serviceId)
                        .addValue("type",staffType).addValue("quantity",request.requiredStaffCount()));
                staffRequirements=1L;
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Required staff count can only change for appointment services");
            }
        if (staffRequirements != null && staffRequirements == 1)
            db.update("""
                    UPDATE service_resource_requirements req SET quantity=:quantity
                    FROM resource_types rt
                    WHERE req.service_id=:id AND rt.id=req.resource_type_id
                      AND rt.organization_id=:org AND rt.category='staff'
                    """, new MapSqlParameterSource().addValue("org",org).addValue("id",serviceId)
                    .addValue("quantity",request.requiredStaffCount()));
        db.update("""
                UPDATE services SET customer_can_choose_staff=:staff,
                    customer_can_choose_resource=:resource
                WHERE organization_id=:org AND id=:id
                """, new MapSqlParameterSource().addValue("org",org).addValue("id",serviceId)
                .addValue("staff",request.customerCanChooseStaff())
                .addValue("resource",request.customerCanChooseResource()));
        return serviceBookingOptions(org, serviceId);
    }

    @Transactional
    public Object replaceResourceRequirements(long org, long serviceId,
                                               BackofficeDto.ResourceRequirements request) {
        service(org, serviceId);
        var seen = new java.util.HashSet<Long>();
        var staffTypes = new java.util.HashSet<Long>();
        for (var req : request.requirements()) {
            if (!seen.add(req.resourceTypeId()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate resource type");
            var type = one("SELECT category FROM resource_types WHERE organization_id=:org AND id=:id AND active",
                    p(org, req.resourceTypeId()));
            if ("staff".equals(type.get("category"))) staffTypes.add(req.resourceTypeId());
        }
        db.update("""
                DELETE FROM service_resource_requirements req
                USING resource_types rt
                WHERE req.service_id=:id AND rt.id=req.resource_type_id
                  AND rt.organization_id=:org AND rt.category<>'staff'
                """, Map.of("id", serviceId, "org", org));
        for (var req : request.requirements()) {
            if (staffTypes.contains(req.resourceTypeId())) continue;
            db.update("""
                    INSERT INTO service_resource_requirements
                        (service_id,resource_type,resource_type_id,quantity,min_capacity)
                    SELECT :service,rt.code,rt.id,:quantity,:capacity FROM resource_types rt
                    WHERE rt.id=:type AND rt.organization_id=:org
                    """, new MapSqlParameterSource().addValue("org", org).addValue("service", serviceId)
                    .addValue("type", req.resourceTypeId()).addValue("quantity", req.quantity())
                    .addValue("capacity", req.minCapacity()));
        }
        Long nonStaffCount=db.queryForObject("""
                SELECT count(*) FROM service_resource_requirements req
                JOIN resource_types rt ON rt.id=req.resource_type_id
                WHERE req.service_id=:id AND rt.organization_id=:org AND rt.category<>'staff' AND rt.active
                """,p(org,serviceId),Long.class);
        if (nonStaffCount==null || nonStaffCount!=1)
            db.update("UPDATE services SET customer_can_choose_resource=false WHERE organization_id=:org AND id=:id",
                    p(org,serviceId));
        return resourceRequirements(org, serviceId);
    }

    public Object location(long org, long id) {
        return one("select * from locations where organization_id=:org and id=:id", p(org, id));
    }

    private void checkCoordinates(Double lat, Double lon) {
        if (!((lat == null && lon == null) ||
              (lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
               && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid location coordinates");
    }

    @Transactional
    public Object updateLocation(long org, long id, BackofficeDto.LocationUpsert r) {
        checkCoordinates(r.latitude(), r.longitude());
        var a = new MapSqlParameterSource().addValue("org", org).addValue("id", id).addValue("name", r.name()).addValue("address", r.address()).addValue("tz", r.timezone()).addValue("active", r.active()).addValue("latitude", r.latitude()).addValue("longitude", r.longitude());
        return one("update locations set name=:name,address=:address,timezone=:tz,active=:active,latitude=:latitude,longitude=:longitude where organization_id=:org and id=:id and archived_at is null returning *", a.getValues());
    }

    @Transactional
    public void archiveLocation(long org, long id) {
        lockScheduleOrganization(org);
        if (db.queryForList("""
                SELECT id FROM locations
                WHERE organization_id=:org AND id=:id AND archived_at IS NULL
                FOR UPDATE
                """, Map.of("org", org, "id", id)).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Location not found");
        boolean hasFutureReservations = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM reservation_items ri
                    JOIN reservations r ON r.id=ri.reservation_id AND r.organization_id=ri.organization_id
                    WHERE ri.organization_id=:org AND ri.location_id=:id
                      AND ri.item_status='active' AND r.status IN ('confirmed','pending')
                      AND ri.starts_at>now()
                ) OR EXISTS (
                    SELECT 1
                    FROM event_occurrences eo
                    JOIN event_series es ON es.id=eo.series_id AND es.organization_id=eo.organization_id
                    JOIN event_registrations er ON er.occurrence_id=eo.id AND er.organization_id=eo.organization_id
                    WHERE eo.organization_id=:org AND es.location_id=:id
                      AND eo.status='scheduled' AND eo.starts_at>now() AND er.status='confirmed'
                )
                """, Map.of("org", org, "id", id), Boolean.class));
        if (hasFutureReservations)
            throw new ApiException(HttpStatus.CONFLICT, "LOCATION_HAS_ACTIVE_RESERVATIONS",
                    "Cancel future reservations and event registrations before archiving this location");
        db.update("""
                UPDATE locations SET active=false,archived_at=now()
                WHERE organization_id=:org AND id=:id AND archived_at IS NULL
                """, Map.of("org", org, "id", id));
    }

    public Object member(long org, long id) {
        return one("select * from organization_members where organization_id=:org and id=:id", p(org, id));
    }

    @Transactional
    public Object updateMember(long org, long id, BackofficeDto.MemberUpsert r) {
        var currentPermissions = one("""
                SELECT can_manage_bookings,can_manage_own_bookings
                FROM organization_members WHERE organization_id=:org AND id=:id AND archived_at IS NULL
                """, p(org, id));
        boolean canManageBookings = r.canManageBookings() == null
                ? Boolean.TRUE.equals(currentPermissions.get("can_manage_bookings"))
                : r.canManageBookings();
        boolean canManageOwnBookings = r.canManageOwnBookings() == null
                ? Boolean.TRUE.equals(currentPermissions.get("can_manage_own_bookings"))
                : r.canManageOwnBookings();
        if (canManageBookings && canManageOwnBookings)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "A worker cannot manage both colleagues' bookings and only their own bookings");
        var a = new MapSqlParameterSource().addValue("org", org).addValue("id", id).addValue("name", r.displayName()).addValue("role", r.role() == null ? "worker" : r.role()).addValue("active", r.active()).addValue("providesServices",r.providesServices()).addValue("manage",r.canManageBookings()).addValue("manageOwn",r.canManageOwnBookings()).addValue("approve",r.canApproveOwnAbsence());
        var updated = one("update organization_members set display_name=:name,role=:role,active=:active,provides_services=COALESCE(CAST(:providesServices AS boolean),provides_services),can_manage_bookings=COALESCE(CAST(:manage AS boolean),can_manage_bookings),can_manage_own_bookings=COALESCE(CAST(:manageOwn AS boolean),can_manage_own_bookings),can_approve_own_absence=COALESCE(CAST(:approve AS boolean),can_approve_own_absence) where organization_id=:org and id=:id and archived_at is null returning *", a.getValues());
        staffResourceService.synchronizeMember(org, id);
        return updated;
    }

    @Transactional
    public void archiveMember(long org, long id) {
        lockScheduleOrganization(org);
        var rows = db.queryForList("""
                SELECT role FROM organization_members
                WHERE organization_id=:org AND id=:id AND archived_at IS NULL
                FOR UPDATE
                """, Map.of("org", org, "id", id));
        if (rows.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Member not found");
        if (!"worker".equals(rows.getFirst().get("role")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Only workers can be removed from the team");
        boolean hasFutureReservations = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM reservation_items ri
                    JOIN reservations r ON r.id=ri.reservation_id AND r.organization_id=ri.organization_id
                    WHERE ri.organization_id=:org AND ri.member_id=:id
                      AND ri.item_status='active' AND r.status IN ('confirmed','pending')
                      AND ri.starts_at>now()
                )
                """, Map.of("org", org, "id", id), Boolean.class));
        if (hasFutureReservations)
            throw new ApiException(HttpStatus.CONFLICT, "MEMBER_HAS_ACTIVE_RESERVATIONS",
                    "Cancel or reassign future reservations before removing this worker");
        db.update("""
                UPDATE organization_members
                SET active=false,archived_at=now()
                WHERE organization_id=:org AND id=:id AND archived_at IS NULL
                """, Map.of("org", org, "id", id));
        staffResourceService.synchronizeMember(org, id);
    }

    public Object memberServices(long org, long id) {
        member(org, id);
        return db.queryForList("select ws.service_id,s.name,ws.active from worker_services ws join services s on s.id=ws.service_id where ws.organization_id=:org and ws.member_id=:id order by s.name", p(org, id));
    }

    @Transactional
    public Object assignService(long org, long id, long serviceId, BackofficeDto.Assignment r) {
        member(org, id);
        service(org, serviceId);
        var a = Map.of("org", org, "id", id, "sid", serviceId, "active", r.active());
        return one("insert into worker_services(organization_id,member_id,service_id,active) values(:org,:id,:sid,:active) on conflict(member_id,service_id) do update set active=excluded.active returning *", a);
    }

    public Object customer(long org, long id) {
        return one("select * from customers where organization_id=:org and id=:id", p(org, id));
    }

    @Transactional
    public Object updateCustomer(long org, long id, BackofficeDto.CustomerUpsert r) {
        var a = new MapSqlParameterSource().addValue("org", org).addValue("id", id).addValue("name", r.name()).addValue("email", r.email()).addValue("phone", r.phone()).addValue("notes", r.notes());
        return one("update customers set name=:name,email=:email,phone=:phone,notes=:notes where organization_id=:org and id=:id returning *", a.getValues());
    }

    @Transactional
    public void deleteCustomer(long org, long id) {
        if (db.queryForList("""
                SELECT id FROM customers
                WHERE organization_id=:org AND id=:id
                FOR UPDATE
                """, p(org, id)).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Customer not found");

        db.update("""
                UPDATE reservations SET customer_id=NULL
                WHERE organization_id=:org AND customer_id=:id
                """, p(org, id));
        db.update("""
                UPDATE waitlist_entries
                SET customer_id=NULL,
                    status=CASE WHEN status IN ('waiting','offered') THEN 'cancelled' ELSE status END
                WHERE organization_id=:org AND customer_id=:id
                """, p(org, id));
        db.update("DELETE FROM customers WHERE organization_id=:org AND id=:id", p(org, id));
    }

    public Object reservation(long org, long id, Authentication authentication) {
        var head = one("select * from reservations where organization_id=:org and id=:id", p(org, id));
        var items = db.queryForList("""
                SELECT ri.*,s.name AS service_name
                FROM reservation_items ri
                JOIN services s ON s.organization_id=ri.organization_id AND s.id=ri.service_id
                WHERE ri.organization_id=:org AND ri.reservation_id=:id
                ORDER BY ri.starts_at
                """, p(org, id));
        boolean canManage = organizationSecurity.canManageReservation(org, id, authentication);
        if (organizationSecurity.canManageBookings(org, authentication)) {
            return Map.of("reservation", head, "items", items, "canManage", canManage);
        }
        var ownItems = db.queryForList("""
                SELECT ri.*,s.name AS service_name FROM reservation_items ri
                JOIN services s ON s.organization_id=ri.organization_id AND s.id=ri.service_id
                WHERE ri.organization_id=:org AND ri.reservation_id=:id AND (
                  EXISTS (SELECT 1 FROM organization_members m WHERE m.organization_id=ri.organization_id
                    AND m.id=ri.member_id AND m.user_id=:user AND m.active=true)
                  OR EXISTS (SELECT 1 FROM reservation_resources rr
                    JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id
                    JOIN organization_members m ON m.id=res.member_id AND m.organization_id=res.organization_id
                    WHERE rr.organization_id=ri.organization_id AND rr.reservation_item_id=ri.id
                    AND m.user_id=:user AND m.active=true)
                ) ORDER BY ri.starts_at
                """, Map.of("org",org,"id",id,"user",organizationSecurity.authenticatedUserId(authentication)));
        return Map.of("reservation", head, "items", ownItems, "canManage", canManage);
    }

    public Object schedule(long org, long id, Authentication authentication, java.time.LocalDate from, java.time.LocalDate to) {
        if (from != null && to != null && !from.isBefore(to))
            throw new ApiException(HttpStatus.BAD_REQUEST, "from must be before to");
        var range = new MapSqlParameterSource().addValue("org",org).addValue("id",id)
                .addValue("from",from).addValue("to",to);
        String period = " AND (CAST(:from AS date) IS NULL OR upper(sr.effective_dates) IS NULL OR upper(sr.effective_dates) > CAST(:from AS date)) "
                + " AND (CAST(:to AS date) IS NULL OR lower(sr.effective_dates) < CAST(:to AS date)) ";
        location(org, id);
        if (organizationSecurity.canWrite(org, authentication)) {
            return db.queryForList("SELECT sr.* FROM schedule_rules sr WHERE sr.organization_id=:org AND sr.location_id=:id "
                    + period + " ORDER BY sr.weekday,sr.start_time,sr.id", range);
        }
        return db.queryForList("""
                SELECT sr.* FROM schedule_rules sr
                JOIN organization_members m ON m.organization_id=sr.organization_id AND m.id=sr.member_id
                WHERE sr.organization_id=:org AND sr.location_id=:id
                  AND m.user_id=:user AND m.active=true
                """ + period + " ORDER BY sr.weekday,sr.start_time,sr.id",
                range.addValue("user", organizationSecurity.authenticatedUserId(authentication)));
    }

    @Transactional
    public Object addSchedule(long org, BackofficeDto.ScheduleCreate r) {
        member(org, r.memberId());
        location(org, r.locationId());
        if (!r.startTime().isBefore(r.endTime())) throw new ApiException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        lockScheduleOrganization(org);
        var a = new MapSqlParameterSource().addValue("org", org).addValue("loc", r.locationId()).addValue("member", r.memberId()).addValue("day", r.weekday()).addValue("start", r.startTime()).addValue("end", r.endTime()).addValue("from", r.effectiveFrom()).addValue("to", r.effectiveTo());
        var inserted = one("insert into schedule_rules(organization_id,location_id,member_id,kind,weekday,start_time,end_time,effective_dates) values(:org,:loc,:member,'working',:day,:start,:end,daterange(:from,:to,'[)')) returning *", a.getValues());
        memberScheduleConflictValidator.validate(org, r.memberId());
        return inserted;
    }

    private void checkScheduleScope(long org, long locationId, Long memberId, String kind) {
        location(org, locationId);
        if ("working".equals(kind) && memberId != null) member(org, memberId);
        else if (!"open".equals(kind) || memberId != null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid schedule scope");
    }

    private void checkDateRange(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid date range (maximum 367 days)");
    }

    public Object datedSchedule(long org, long locationId, Long memberId, String kind,
                                java.time.LocalDate from, java.time.LocalDate to) {
        checkScheduleScope(org, locationId, memberId, kind);
        checkDateRange(from, to);
        var args = new MapSqlParameterSource().addValue("org",org).addValue("loc",locationId)
                .addValue("member",memberId).addValue("kind",kind).addValue("from",from).addValue("to",to);
        return db.queryForList("""
                SELECT d.work_date, d.id, i.start_time, i.end_time
                FROM schedule_date_overrides d
                LEFT JOIN schedule_date_override_intervals i ON i.override_id=d.id
                WHERE d.organization_id=:org AND d.location_id=:loc AND d.kind=:kind
                  AND ((:kind='open' AND d.member_id IS NULL) OR (:kind='working' AND d.member_id=:member))
                  AND d.work_date BETWEEN :from AND :to
                ORDER BY d.work_date, i.start_time
                """,args);
    }

    @Transactional
    public Object replaceDatedSchedule(long org, long locationId, Long memberId,
                                       String kind, BackofficeDto.DatedSchedule request,
                                       boolean dryRun, boolean cancelConflictingReservations) {
        checkScheduleScope(org, locationId, memberId, kind);
        lockScheduleOrganization(org);
        if (request == null || request.days() == null || request.days().size() > 62)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Maximum 62 dates per request");
        var seen = new java.util.HashSet<java.time.LocalDate>();
        for (var day : request.days()) {
            if (day == null || day.date() == null || day.intervals() == null
                    || day.date().isBefore(java.time.LocalDate.now()) || !seen.add(day.date()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid, past or duplicate date");
            var sorted = new java.util.ArrayList<>(day.intervals());
            if (sorted.contains(null)) throw new ApiException(HttpStatus.BAD_REQUEST, "Null interval");
            sorted.sort(java.util.Comparator.comparing(BackofficeDto.TimeInterval::from,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
            java.time.LocalTime previousEnd = null;
            for (var interval : sorted) {
                if (interval.from()==null || interval.to()==null || !interval.from().isBefore(interval.to())
                        || (previousEnd!=null && interval.from().isBefore(previousEnd)))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or overlapping intervals");
                previousEnd=interval.to();
            }
        }
        for (var day : request.days()) {
            var args = new MapSqlParameterSource().addValue("org",org).addValue("loc",locationId)
                    .addValue("member",memberId).addValue("kind",kind).addValue("date",day.date());
            db.update("""
                DELETE FROM schedule_date_overrides WHERE organization_id=:org AND location_id=:loc
                  AND kind=:kind AND work_date=:date
                  AND ((:kind='open' AND member_id IS NULL) OR (:kind='working' AND member_id=:member))
                """,args);
            Long id=db.queryForObject("""
                INSERT INTO schedule_date_overrides (organization_id,location_id,member_id,kind,work_date)
                VALUES (:org,:loc,:member,:kind,:date) RETURNING id
                """,args,Long.class);
            for (var interval : day.intervals()) {
                db.update("""
                    INSERT INTO schedule_date_override_intervals(override_id,start_time,end_time)
                    VALUES (:id,:start,:end)
                    """,new MapSqlParameterSource().addValue("id",id)
                        .addValue("start",interval.from()).addValue("end",interval.to()));
            }
        }
        if ("working".equals(kind)) memberScheduleConflictValidator.validate(org, memberId);
        var result = finishScheduleChange(org, locationId, memberId, kind, dryRun, cancelConflictingReservations);
        return Map.of("updatedDates", request.days().size(), "result", result);
    }

    @Transactional
    public Object deleteDatedSchedule(long org, long locationId, Long memberId,
                                    String kind, java.time.LocalDate date,
                                    boolean dryRun, boolean cancelConflictingReservations) {
        checkScheduleScope(org, locationId, memberId, kind);
        lockScheduleOrganization(org);
        if (date.isBefore(java.time.LocalDate.now()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot change past dates");
        db.update("""
            DELETE FROM schedule_date_overrides WHERE organization_id=:org AND location_id=:loc
              AND kind=:kind AND work_date=:date
              AND ((:kind='open' AND member_id IS NULL) OR (:kind='working' AND member_id=:member))
            """,new MapSqlParameterSource().addValue("org",org).addValue("loc",locationId)
                .addValue("member",memberId).addValue("kind",kind).addValue("date",date));
        if ("working".equals(kind)) memberScheduleConflictValidator.validate(org, memberId);
        return finishScheduleChange(org, locationId, memberId, kind, dryRun, cancelConflictingReservations);
    }

    /**
     * Lock the same organization row as BookingService.create/reschedule, so a new
     * booking cannot be inserted between a schedule edit and its conflict check.
     */
    private void lockScheduleOrganization(long org) {
        if (db.queryForList("SELECT id FROM organizations WHERE id=:org FOR UPDATE", Map.of("org", org)).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");
    }

    /**
     * Check each future active item against the NEW effective schedule.
     * A date override (including an empty day) replaces the weekly template.
     * The SQL compares local times using the location timezone, like BookingService.
     */
    private java.util.List<Map<String, Object>> scheduleConflicts(long org, long locationId,
                                                                   Long memberId, String kind) {
        var p = new MapSqlParameterSource().addValue("org", org).addValue("loc", locationId)
                .addValue("member", memberId).addValue("kind", kind);
        return db.queryForList("""
            WITH future_items AS (
                SELECT ri.id AS item_id, ri.reservation_id, ri.member_id, ri.starts_at, ri.ends_at,
                       r.customer_name, r.customer_email,
                       (ri.starts_at AT TIME ZONE l.timezone)::date AS local_day,
                       (ri.ends_at AT TIME ZONE l.timezone)::date AS local_end_day,
                       (ri.starts_at AT TIME ZONE l.timezone)::time AS local_start,
                       (ri.ends_at AT TIME ZONE l.timezone)::time AS local_end
                FROM reservation_items ri
                JOIN reservations r ON r.id=ri.reservation_id AND r.organization_id=ri.organization_id
                JOIN locations l ON l.id=ri.location_id AND l.organization_id=ri.organization_id
                WHERE ri.organization_id=:org AND ri.location_id=:loc
                  AND (CAST(:member AS bigint) IS NULL OR ri.member_id=:member)
                  AND ri.item_status='active' AND r.status IN ('confirmed','pending')
                  AND ri.starts_at>now()
            ), violations AS (
                SELECT f.* FROM future_items f
                WHERE f.local_day <> f.local_end_day OR NOT (
                    (EXISTS (
                        SELECT 1 FROM schedule_date_overrides d
                        WHERE d.organization_id=:org AND d.location_id=:loc AND d.kind=:kind
                          AND d.work_date=f.local_day
                          AND ((:kind='open' AND d.member_id IS NULL)
                            OR (:kind='working' AND d.member_id=f.member_id))
                    ) AND EXISTS (
                        SELECT 1 FROM schedule_date_overrides d
                        JOIN schedule_date_override_intervals i ON i.override_id=d.id
                        WHERE d.organization_id=:org AND d.location_id=:loc AND d.kind=:kind
                          AND d.work_date=f.local_day
                          AND ((:kind='open' AND d.member_id IS NULL)
                            OR (:kind='working' AND d.member_id=f.member_id))
                          AND i.start_time<=f.local_start AND i.end_time>=f.local_end
                    )) OR (
                        NOT EXISTS (
                            SELECT 1 FROM schedule_date_overrides d
                            WHERE d.organization_id=:org AND d.location_id=:loc AND d.kind=:kind
                              AND d.work_date=f.local_day
                              AND ((:kind='open' AND d.member_id IS NULL)
                                OR (:kind='working' AND d.member_id=f.member_id))
                        ) AND EXISTS (
                            SELECT 1 FROM schedule_rules sr
                            WHERE sr.organization_id=:org AND sr.location_id=:loc AND sr.kind=:kind
                              AND ((:kind='open' AND sr.member_id IS NULL)
                                OR (:kind='working' AND sr.member_id=f.member_id))
                              AND sr.weekday=EXTRACT(ISODOW FROM f.local_day)
                              AND sr.effective_dates @> f.local_day
                              AND sr.start_time<=f.local_start AND sr.end_time>=f.local_end
                        )
                    )
                )
            )
            SELECT v.reservation_id AS "reservationId", v.item_id AS "reservationItemId",
                   v.customer_name AS "customerName", v.customer_email AS "customerEmail",
                   v.starts_at AS "startsAt", v.ends_at AS "endsAt",
                   NOT EXISTS (
                       SELECT 1 FROM payments p WHERE p.reservation_id=v.reservation_id
                         AND p.status NOT IN ('failed','refunded')
                   ) AND NOT EXISTS (
                       SELECT 1 FROM group_session_attendees ga
                       WHERE ga.reservation_id=v.reservation_id AND ga.status='reserved'
                   ) AS "canAutoCancel"
            FROM violations v
            ORDER BY v.starts_at,v.reservation_id,v.item_id
            LIMIT 501
            """, p);
    }

    /**
     * Preview is transactionally rolled back. Normal edits fail with 409 if any
     * booking would be invalidated, unless cancellation was explicitly requested.
     */
    private Object finishScheduleChange(long org, long locationId, Long memberId, String kind,
                                        boolean dryRun, boolean cancelConflictingReservations) {
        var conflicts = scheduleConflicts(org, locationId, memberId, kind);
        if (conflicts.size() > 500)
            throw new ApiException(HttpStatus.CONFLICT,
                    "More than 500 affected items; narrow the change and handle bookings manually");
        var reservationIds = new java.util.LinkedHashSet<Long>();
        var cannotCancel = new java.util.LinkedHashSet<Long>();
        for (var row : conflicts) {
            long id = ((Number) row.get("reservationId")).longValue();
            reservationIds.add(id);
            if (!Boolean.TRUE.equals(row.get("canAutoCancel"))) cannotCancel.add(id);
        }
        if (dryRun) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return Map.of("dryRun", true, "conflictCount", reservationIds.size(),
                    "conflicts", conflicts, "autoCancellationBlockedIds", cannotCancel);
        }
        if (!reservationIds.isEmpty() && !cancelConflictingReservations)
            throw new ScheduleConflictException(conflicts,
                    "Schedule change conflicts with existing reservations. Preview with dryRun=true, "
                    + "or explicitly set cancelConflictingReservations=true.");
        if (!cannotCancel.isEmpty())
            throw new ScheduleConflictException(conflicts,
                    "Some affected reservations have payments or group attendance and require manual handling.");
        for (long id : reservationIds) {
            var args = new MapSqlParameterSource().addValue("org",org).addValue("id",id)
                    .addValue("kind",kind).addValue("loc",locationId).addValue("member",memberId);
            int changed = db.update("""
                UPDATE reservations SET status='cancelled'
                WHERE organization_id=:org AND id=:id AND status IN ('confirmed','pending')
                """, args);
            if (changed != 1)
                throw new ApiException(HttpStatus.CONFLICT, "Reservation changed concurrently: " + id);
            // Existing DB trigger cancels reservation_items and releases resources.
            db.update("""
                INSERT INTO reservation_events(reservation_id,event_type,details)
                VALUES (:id,'cancelled_by_schedule_change',
                    jsonb_build_object('kind',:kind,'locationId',:loc,'memberId',:member))
                """, args);
        }
        return Map.of("dryRun", false, "conflictCount", reservationIds.size(),
                "cancelledReservationIds", reservationIds, "conflicts", conflicts);
    }

    public Object openingHours(long org, long locationId) {
        location(org, locationId);
        return weeklyHours(org, locationId, null, "open");
    }

    public Object workingHours(long org, long locationId, long memberId) {
        location(org, locationId);
        member(org, memberId);
        return weeklyHours(org, locationId, memberId, "working");
    }

    private Object weeklyHours(long org, long locationId, Long memberId, String kind) {
        var args = new MapSqlParameterSource().addValue("org", org).addValue("loc", locationId)
                .addValue("member", memberId).addValue("kind", kind);
        return db.queryForList("""
                SELECT weekday, start_time AS "from", end_time AS "to"
                FROM schedule_rules
                WHERE organization_id=:org AND location_id=:loc AND kind=:kind
                  AND (CAST(:member AS bigint) IS NULL OR member_id=:member)
                  AND effective_dates @> CURRENT_DATE
                ORDER BY weekday,start_time,id
                """, args);
    }

    @Transactional
    public Object replaceWeeklySchedule(long org, long locationId, Long memberId,
                                        String kind, BackofficeDto.WeeklySchedule request,
                                        boolean dryRun, boolean cancelConflictingReservations) {
        location(org, locationId);
        lockScheduleOrganization(org);
        if ("working".equals(kind)) {
            if (memberId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "memberId required");
            member(org, memberId);
        } else if (!"open".equals(kind) || memberId != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid schedule kind");
        }
        var seen = new java.util.HashSet<Integer>();
        var days = request.days();
        if (days.size() > 7) throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate weekday");
        for (var day : days) {
            if (day == null || day.weekday() < 1 || day.weekday() > 7 || !seen.add(day.weekday()) || day.intervals() == null)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or duplicate weekday");
            var intervals = new java.util.ArrayList<>(day.intervals());
            if (intervals.stream().anyMatch(i -> i == null || i.from() == null || i.to() == null))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid time interval");
            intervals.sort(java.util.Comparator.comparing(BackofficeDto.TimeInterval::from));
            java.time.LocalTime previousEnd = null;
            for (var interval : intervals) {
                if (!interval.from().isBefore(interval.to()))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid time interval");
                if (previousEnd != null && interval.from().isBefore(previousEnd))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Overlapping intervals");
                previousEnd = interval.to();
            }
        }
        var args = new MapSqlParameterSource().addValue("org", org).addValue("loc", locationId)
                .addValue("member", memberId).addValue("kind", kind);
        String scope = " organization_id=:org AND location_id=:loc AND kind=:kind "
                + ("open".equals(kind) ? " AND member_id IS NULL " : " AND member_id=:member ");
        db.update("DELETE FROM schedule_rules WHERE " + scope
                + " AND lower(effective_dates) >= CURRENT_DATE", args);
        db.update("UPDATE schedule_rules SET effective_dates=daterange(lower(effective_dates),CURRENT_DATE,'[)') "
                + "WHERE " + scope + " AND lower(effective_dates) < CURRENT_DATE "
                + "AND (upper_inf(effective_dates) OR upper(effective_dates)>CURRENT_DATE)", args);
        for (var day : days) {
            for (var interval : day.intervals()) {
                db.update("""
                    INSERT INTO schedule_rules
                    (organization_id,location_id,member_id,kind,weekday,start_time,end_time,effective_dates)
                    VALUES (:org,:loc,:member,:kind,:weekday,:start,:end,daterange(CURRENT_DATE,NULL,'[)'))
                    """, new MapSqlParameterSource().addValue("org",org).addValue("loc",locationId)
                        .addValue("member",memberId).addValue("kind",kind)
                        .addValue("weekday",day.weekday()).addValue("start",interval.from())
                        .addValue("end",interval.to()));
            }
        }
        if ("working".equals(kind)) memberScheduleConflictValidator.validate(org, memberId);
        var result = finishScheduleChange(org, locationId, memberId, kind, dryRun, cancelConflictingReservations);
        return Map.of("schedule", weeklyHours(org, locationId, memberId, kind), "result", result);
    }

    public Object absences(long org, Authentication authentication, int page, int size,
            java.time.OffsetDateTime from, java.time.OffsetDateTime to, String q, String sort) {
        validatePeriod(from, to);
        var params = pageParams(page, size).addValue("org", org).addValue("from", from).addValue("to", to).addValue("q", contains(q));
        String where = " FROM availability_overrides ao WHERE ao.organization_id=:org "
                + " AND (CAST(:from AS timestamptz) IS NULL OR upper(ao.period) > CAST(:from AS timestamptz)) "
                + " AND (CAST(:to AS timestamptz) IS NULL OR lower(ao.period) < CAST(:to AS timestamptz)) ";
        where += " AND (CAST(:q AS text) IS NULL OR ao.reason ILIKE :q ESCAPE '\\' OR ao.override_type ILIKE :q ESCAPE '\\') ";
        if (!organizationSecurity.canManageBookings(org, authentication)) {
            params.addValue("user", organizationSecurity.authenticatedUserId(authentication));
            where += " AND EXISTS (SELECT 1 FROM organization_members m WHERE m.id=ao.member_id "
                    + " AND m.organization_id=ao.organization_id AND m.user_id=:user AND m.active=true) ";
        }
        return paged("SELECT ao.*", where, sortOrder(sort, Map.of("id", "ao.id", "period", "lower(ao.period)", "type", "ao.override_type"), "ao.id"), params, page, size);
    }

    @Transactional
    public Object addAbsence(long org, BackofficeDto.AbsenceCreate r) {
        if (r.locationId() == null && r.memberId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "locationId or memberId required");
        if (r.locationId() != null) location(org, r.locationId());
        if (r.memberId() != null) member(org, r.memberId());
        if (!r.startsAt().isBefore(r.endsAt())) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid period");
        var a = new MapSqlParameterSource().addValue("org", org).addValue("loc", r.locationId()).addValue("member", r.memberId()).addValue("start", r.startsAt()).addValue("end", r.endsAt()).addValue("reason", r.reason()).addValue("type", r.type() == null ? "unavailable" : r.type());
        return one("insert into availability_overrides(organization_id,location_id,member_id,override_type,period,reason) values(:org,:loc,:member,:type,tstzrange(:start,:end,'[)'),:reason) returning *", a.getValues());
    }

    public Object getOrganization(long org) {

        return one("""
                SELECT id, public_id, name, email, phone, default_timezone,
                       booking_enabled, registration_status, created_at
                FROM organizations WHERE id=:org
                """, Map.of("org", org));
    }

    @Transactional
    public Object updateOrganization(long org,

                                     BackofficeDto.OrganizationUpdate r) {

        var args = new MapSqlParameterSource()
                .addValue("org", org)
                .addValue("name", r.name().trim())
                .addValue("email", r.email())
                .addValue("phone", r.phone())
                .addValue("tz", r.defaultTimezone())
                .addValue("enabled", r.bookingEnabled());
        return one("""
                UPDATE organizations
                SET name=:name, email=:email, phone=:phone,
                    default_timezone=:tz, booking_enabled=:enabled
                WHERE id=:org AND registration_status='active'
                RETURNING id,public_id,name,email,phone,default_timezone,
                          booking_enabled,registration_status
                """, args.getValues());
    }

    @Transactional
    public void deleteOrganization(long org) {
        organizationDeletionService.delete(org);
    }

    @Transactional
    public Object createLocation(long org, BackofficeDto.CreateLocation r) {
        checkCoordinates(r.latitude(), r.longitude());
        var a = new MapSqlParameterSource().addValue("org", org).addValue("name", r.name())
                .addValue("tz", r.timezone()).addValue("address", r.address()).addValue("latitude", r.latitude()).addValue("longitude", r.longitude());
        return one("""
                INSERT INTO locations(organization_id,name,timezone,address,latitude,longitude)
                VALUES(:org,:name,:tz,:address,:latitude,:longitude) RETURNING *
                """, a.getValues());
    }

    @Transactional
    public Object createService(long org, BackofficeDto.CreateService r) {
        var a = new MapSqlParameterSource().addValue("org", org).addValue("name", r.name())
                .addValue("description", r.description());
        return one("""
                INSERT INTO services(organization_id,name,description)
                VALUES(:org,:name,:description) RETURNING id,name
                """, a.getValues());
    }

    public Object customers(long org, String name, String email, int page, int size, String sort, String q) {
        var params = pageParams(page, size).addValue("org", org)
                .addValue("name", contains(name)).addValue("email", contains(email)).addValue("q", contains(q));
        String where = " FROM customers c WHERE c.organization_id=:org "
                + " AND (CAST(:name AS text) IS NULL OR c.name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:email AS text) IS NULL OR c.email ILIKE :email ESCAPE '\\') ";
        where += " AND (CAST(:q AS text) IS NULL OR c.name ILIKE :q ESCAPE '\\' OR c.email ILIKE :q ESCAPE '\\' OR c.phone ILIKE :q ESCAPE '\\') ";
        String order = sortOrder(sort, Map.of("id", "c.id", "name", "c.name", "email", "c.email", "phone", "c.phone"), "c.id");
        return paged("SELECT c.id,c.name,c.email,c.phone,c.notes", where, order, params, page, size);
    }

    public Object bookingCustomers(long org, int page, int size, String q, String sort) {
        var params = pageParams(page, size).addValue("org", org).addValue("q", contains(q));
        String where = """
                FROM (
                    SELECT MIN(r.id) AS id,
                           (array_agg(r.customer_name ORDER BY r.created_at DESC, r.id DESC))[1] AS name,
                           NULLIF(lower(btrim(r.customer_email)), '') AS email,
                           (array_agg(NULLIF(btrim(r.customer_phone), '') ORDER BY r.created_at DESC, r.id DESC)
                               FILTER (WHERE NULLIF(btrim(r.customer_phone), '') IS NOT NULL))[1] AS phone,
                           COUNT(*) AS reservation_count,
                           MAX(r.created_at) AS last_reservation_at
                    FROM reservations r
                    WHERE r.organization_id=:org
                    GROUP BY NULLIF(lower(btrim(r.customer_email)), ''),
                             CASE WHEN NULLIF(lower(btrim(r.customer_email)), '') IS NULL THEN r.id END
                ) c
                WHERE (CAST(:q AS text) IS NULL OR c.name ILIKE :q ESCAPE '\\'
                    OR COALESCE(c.email, '') ILIKE :q ESCAPE '\\'
                    OR COALESCE(c.phone, '') ILIKE :q ESCAPE '\\')
                """;
        String order = sortOrder(sort, Map.of(
                "id", "c.id", "name", "c.name", "email", "c.email",
                "phone", "c.phone", "reservationCount", "c.reservation_count",
                "lastReservationAt", "c.last_reservation_at"), "c.id");
        return paged("""
                SELECT c.id,c.name,c.email,c.phone,c.reservation_count,c.last_reservation_at
                """, where, order, params, page, size);
    }

    public Object bookingCustomerReservations(long org, long id) {
        var params = new MapSqlParameterSource().addValue("org", org).addValue("id", id);
        var selected = db.queryForList("""
                SELECT NULLIF(lower(btrim(customer_email)), '') AS email
                FROM reservations
                WHERE organization_id=:org AND id=:id
                """, params);
        if (selected.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Reservation not found");
        Object email = selected.getFirst().get("email");
        params.addValue("email", email);
        var reservations = db.queryForList("""
                SELECT r.id,r.customer_name,r.customer_email,r.customer_phone,r.status,
                       r.total_amount,r.currency,r.created_at,
                       (SELECT MIN(ri.starts_at) FROM reservation_items ri
                        WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id) AS appointment_at
                FROM reservations r
                WHERE r.organization_id=:org
                  AND ((CAST(:email AS text) IS NULL AND r.id=:id)
                    OR (CAST(:email AS text) IS NOT NULL
                        AND NULLIF(lower(btrim(r.customer_email)), '')=:email))
                ORDER BY appointment_at DESC NULLS LAST,r.created_at DESC,r.id DESC
                """, params);
        return reservations;
    }

    @Transactional
    public Object createCustomer(long org, BackofficeDto.CreateCustomer r) {
        var a = new MapSqlParameterSource().addValue("org", org).addValue("name", r.name())
                .addValue("email", r.email()).addValue("phone", r.phone()).addValue("notes", r.notes());
        return one("""
                INSERT INTO customers(organization_id,name,email,phone,notes)
                VALUES(:org,:name,:email,:phone,:notes) RETURNING id,name,email,notes
                """, a.getValues());
    }

    public Object reservations(long org, Long locationId, Authentication authentication,
            String status, boolean upcomingOnly, Long memberId, Long resourceId, java.time.OffsetDateTime from, java.time.OffsetDateTime to,
            int page, int size, String q, String sort) {
        validatePeriod(from, to);
        if (locationId != null) location(org, locationId);
        var params = pageParams(page, size).addValue("org", org).addValue("loc", locationId)
                .addValue("status", blankToNull(status)).addValue("upcomingOnly", upcomingOnly)
                .addValue("member", memberId).addValue("resource", resourceId)
                .addValue("from", from).addValue("to", to)
                .addValue("q", searchTokens(q));
        String where = " FROM reservations r WHERE r.organization_id=:org "
                + " AND (CAST(:loc AS bigint) IS NULL OR r.location_id=:loc) "
                + " AND (CAST(:status AS text) IS NULL OR r.status=:status) "
                + " AND (:upcomingOnly=false OR EXISTS (SELECT 1 FROM reservation_items future_item"
                + " WHERE future_item.organization_id=r.organization_id AND future_item.reservation_id=r.id"
                + " AND future_item.item_status='active' AND future_item.starts_at>now())) "
                + " AND (CAST(:member AS bigint) IS NULL OR EXISTS ("
                + " SELECT 1 FROM reservation_items ri WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id"
                + " AND (ri.member_id=:member OR EXISTS (SELECT 1 FROM reservation_resources rr"
                + " JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id"
                + " JOIN resource_types rt ON rt.id=res.resource_type_id AND rt.organization_id=res.organization_id"
                + " WHERE rr.organization_id=ri.organization_id AND rr.reservation_item_id=ri.id"
                + " AND rt.category='staff' AND res.member_id=:member)))) "
                + " AND (CAST(:resource AS bigint) IS NULL OR EXISTS ("
                + " SELECT 1 FROM reservation_items ri JOIN reservation_resources rr"
                + " ON rr.organization_id=ri.organization_id AND rr.reservation_item_id=ri.id"
                + " JOIN resources res ON res.organization_id=rr.organization_id AND res.id=rr.resource_id"
                + " JOIN resource_types rt ON rt.organization_id=res.organization_id AND rt.id=res.resource_type_id"
                + " WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id"
                + " AND res.id=:resource AND rt.category<>'staff')) "
                + " AND (CAST(:from AS timestamptz) IS NULL OR r.created_at >= CAST(:from AS timestamptz)) "
                + " AND (CAST(:to AS timestamptz) IS NULL OR r.created_at < CAST(:to AS timestamptz)) ";
        where += """
                 AND (CAST(:q AS text[]) IS NULL OR NOT EXISTS (
                     SELECT 1 FROM unnest(CAST(:q AS text[])) AS search_token(value)
                     WHERE NOT (
                         r.customer_name ILIKE '%' || search_token.value || '%' ESCAPE '\\'
                         OR COALESCE(r.customer_email,'') ILIKE '%' || search_token.value || '%' ESCAPE '\\'
                     )
                 ))
                """;
        if (!organizationSecurity.canManageBookings(org, authentication)) {
            params.addValue("user", organizationSecurity.authenticatedUserId(authentication));
            where += " AND EXISTS (SELECT 1 FROM reservation_items ri "
                    + " WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id AND ( "
                    + " EXISTS (SELECT 1 FROM organization_members m WHERE m.organization_id=ri.organization_id "
                    + " AND m.id=ri.member_id AND m.user_id=:user AND m.active=true) "
                    + " OR EXISTS (SELECT 1 FROM reservation_resources rr "
                    + " JOIN resources res ON res.id=rr.resource_id AND res.organization_id=rr.organization_id "
                    + " JOIN organization_members m ON m.id=res.member_id AND m.organization_id=res.organization_id "
                    + " WHERE rr.reservation_item_id=ri.id AND rr.organization_id=ri.organization_id "
                    + " AND m.user_id=:user AND m.active=true))) ";
        }
        return paged("""
                SELECT r.id,r.customer_name,r.status,r.total_amount,r.currency,r.created_at,
                       (SELECT MIN(ri.starts_at) FROM reservation_items ri
                        WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id) AS appointment_at,
                       (SELECT MAX(ri.ends_at) FROM reservation_items ri
                        WHERE ri.organization_id=r.organization_id AND ri.reservation_id=r.id) AS appointment_ends_at,
                       (SELECT string_agg(worker_names.display_name, ', ' ORDER BY worker_names.display_name)
                        FROM (
                          SELECT m.display_name
                          FROM reservation_items worker_item
                          JOIN organization_members m ON m.organization_id=worker_item.organization_id
                            AND m.id=worker_item.member_id
                          WHERE worker_item.organization_id=r.organization_id
                            AND worker_item.reservation_id=r.id AND worker_item.member_id IS NOT NULL
                          UNION
                          SELECT assigned.display_name
                          FROM reservation_items assigned_item
                          JOIN reservation_resources rr ON rr.organization_id=assigned_item.organization_id
                            AND rr.reservation_item_id=assigned_item.id
                          JOIN resources res ON res.organization_id=rr.organization_id AND res.id=rr.resource_id
                          JOIN resource_types rt ON rt.organization_id=res.organization_id
                            AND rt.id=res.resource_type_id AND rt.category='staff'
                          JOIN organization_members assigned ON assigned.organization_id=res.organization_id
                            AND assigned.id=res.member_id
                          WHERE assigned_item.organization_id=r.organization_id
                            AND assigned_item.reservation_id=r.id AND res.member_id IS NOT NULL
                        ) worker_names) AS member_name,
                       (SELECT string_agg(DISTINCT res.name, ', ' ORDER BY res.name)
                        FROM reservation_items resource_item
                        JOIN reservation_resources rr ON rr.organization_id=resource_item.organization_id
                          AND rr.reservation_item_id=resource_item.id
                        JOIN resources res ON res.organization_id=rr.organization_id AND res.id=rr.resource_id
                        JOIN resource_types rt ON rt.organization_id=res.organization_id
                          AND rt.id=res.resource_type_id AND rt.category<>'staff'
                        WHERE resource_item.organization_id=r.organization_id
                          AND resource_item.reservation_id=r.id) AS resource_name
                """,
                where, sortOrder(sort, Map.of("id", "r.id", "createdAt", "r.created_at", "status", "r.status", "customerName", "r.customer_name"), "r.id"), params, page, size);
    }

    public Object members(long org, String name, String role, int page, int size, String q, String sort) {
        if (role != null && !role.isBlank() && !List.of("owner", "admin", "worker").contains(role))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid role");
        var params = pageParams(page, size).addValue("org", org)
                .addValue("name", contains(name)).addValue("role", blankToNull(role)).addValue("q", contains(q));
        String where = " FROM organization_members m LEFT JOIN users u ON u.id=m.user_id WHERE m.organization_id=:org AND m.archived_at IS NULL "
                + " AND (CAST(:name AS text) IS NULL OR m.display_name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:role AS text) IS NULL OR m.role=:role) ";
        where += " AND (CAST(:q AS text) IS NULL OR m.display_name ILIKE :q ESCAPE '\\' OR m.role ILIKE :q ESCAPE '\\' OR u.email ILIKE :q ESCAPE '\\') ";
        return paged("SELECT m.id,m.user_id,u.email,m.display_name,m.role,m.active,m.provides_services,m.can_manage_bookings,m.can_manage_own_bookings,m.can_approve_own_absence", where,
                sortOrder(sort, Map.of("id", "m.id", "name", "m.display_name", "role", "m.role", "active", "m.active"), "m.id"), params, page, size);
    }

    public Object services(long org, String name, Boolean active, int page, int size, String q, String sort) {
        var params = pageParams(page, size).addValue("org", org)
                .addValue("name", contains(name)).addValue("active", active).addValue("q", contains(q));
        String where = " FROM services s WHERE s.organization_id=:org "
                + " AND (CAST(:name AS text) IS NULL OR s.name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:active AS boolean) IS NULL OR s.active=:active) ";
        where += " AND (CAST(:q AS text) IS NULL OR s.name ILIKE :q ESCAPE '\\' OR s.description ILIKE :q ESCAPE '\\') ";
        return paged("SELECT s.id,s.name,s.description,s.active", where,
                sortOrder(sort, Map.of("id", "s.id", "name", "s.name", "active", "s.active"), "s.id"), params, page, size);
    }

    public Object locations(long org, String name, Boolean active, int page, int size, String q, String sort) {
        var params = pageParams(page, size).addValue("org", org)
                .addValue("name", contains(name)).addValue("active", active).addValue("q", contains(q));
        String where = " FROM locations l WHERE l.organization_id=:org AND l.archived_at IS NULL "
                + " AND (CAST(:name AS text) IS NULL OR l.name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:active AS boolean) IS NULL OR l.active=:active) ";
        where += " AND (CAST(:q AS text) IS NULL OR l.name ILIKE :q ESCAPE '\\' OR l.address ILIKE :q ESCAPE '\\' OR l.timezone ILIKE :q ESCAPE '\\') ";
        return paged("SELECT l.id,l.name,l.address,l.timezone,l.active,l.latitude,l.longitude", where,
                sortOrder(sort, Map.of("id", "l.id", "name", "l.name", "active", "l.active"), "l.id"), params, page, size);
    }

    public Object users(String q, String name, String email, Boolean verified, Boolean superadmin,
            java.time.OffsetDateTime createdFrom, java.time.OffsetDateTime createdTo,
            int page, int size, String sort) {
        validatePeriod(createdFrom, createdTo);
        var params = pageParams(page, size)
                .addValue("q", contains(q))
                .addValue("name", contains(name))
                .addValue("email", contains(email))
                .addValue("verified", verified)
                .addValue("superadmin", superadmin)
                .addValue("createdFrom", createdFrom)
                .addValue("createdTo", createdTo);
        String where = " FROM users u WHERE true "
                + " AND (CAST(:q AS text) IS NULL OR u.name ILIKE :q ESCAPE '\\' OR u.email ILIKE :q ESCAPE '\\') "
                + " AND (CAST(:name AS text) IS NULL OR u.name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:email AS text) IS NULL OR u.email ILIKE :email ESCAPE '\\') "
                + " AND (CAST(:verified AS boolean) IS NULL OR (u.email_verified_at IS NOT NULL) = :verified) "
                + " AND (CAST(:superadmin AS boolean) IS NULL OR u.is_superadmin = :superadmin) "
                + " AND (CAST(:createdFrom AS timestamptz) IS NULL OR u.created_at >= :createdFrom) "
                + " AND (CAST(:createdTo AS timestamptz) IS NULL OR u.created_at < :createdTo) ";
        return paged("SELECT u.id,u.name,u.email,u.email_verified_at,u.is_superadmin,u.created_at", where,
                sortOrder(sort, Map.of("id", "u.id", "name", "u.name", "email", "u.email",
                        "verified", "u.email_verified_at", "superadmin", "u.is_superadmin",
                        "createdAt", "u.created_at"), "u.id"), params, page, size);
    }

    private static String sortOrder(String sort, Map<String, String> columns, String idColumn) {
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !columns.containsKey(parts[0]) ||
                !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported sort; use column,asc or column,desc");
        String direction = parts[1].equalsIgnoreCase("asc") ? " ASC" : " DESC";
        String column = columns.get(parts[0]);
        return " ORDER BY " + column + direction + (column.equals(idColumn) ? "" : ", " + idColumn + direction);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String contains(String value) {
        String cleaned = blankToNull(value);
        if (cleaned == null) return null;
        return "%" + cleaned.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static String[] searchTokens(String value) {
        String cleaned = blankToNull(value);
        if (cleaned == null) return null;
        return java.util.Arrays.stream(cleaned.split("\\s+"))
                .filter(token -> !token.isBlank())
                .map(token -> token.replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_"))
                .toArray(String[]::new);
    }

    private static void validatePeriod(java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to))
            throw new ApiException(HttpStatus.BAD_REQUEST, "from must be before to");
    }

    private static MapSqlParameterSource pageParams(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size between 1 and 100");
        return new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
    }

    private Map<String, Object> paged(String select, String fromWhere, String order,
            MapSqlParameterSource params, int page, int size) {
        Long total = db.queryForObject("SELECT COUNT(*) " + fromWhere, params, Long.class);
        var items = db.queryForList(select + fromWhere + order + " LIMIT :limit OFFSET :offset", params);
        long count = total == null ? 0 : total;
        return Map.of("items", items, "page", page, "size", size,
                "totalElements", count, "totalPages", (count + size - 1) / size);
    }

    public Map<String, Object> listOrganizations(
            String q, String name, String email, String phone, String timezone, String status,
            Boolean bookingEnabled, java.time.OffsetDateTime createdFrom, java.time.OffsetDateTime createdTo,
            int page, int size, String sort, Authentication authentication) {

        Long userId = organizationSecurity.authenticatedUserId(authentication);
        if (userId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        validatePeriod(createdFrom, createdTo);
        var params = pageParams(page, size);
        String cleanStatus = blankToNull(status);
        if (cleanStatus != null && !List.of("pending_email_verification", "active", "suspended").contains(cleanStatus))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status");

        params.addValue("userId", userId)
                .addValue("q", contains(q))
                .addValue("name", contains(name))
                .addValue("email", contains(email))
                .addValue("phone", contains(phone))
                .addValue("timezone", contains(timezone))
                .addValue("status", cleanStatus)
                .addValue("bookingEnabled", bookingEnabled)
                .addValue("createdFrom", createdFrom)
                .addValue("createdTo", createdTo);

        boolean superadmin = organizationSecurity.isSuperadmin(authentication);
        String fromWhere = superadmin ? """
                FROM organizations o
                LEFT JOIN organization_booking_config c ON c.organization_id = o.id
                WHERE true
                """ : """
                FROM organizations o
                LEFT JOIN organization_booking_config c ON c.organization_id = o.id
                WHERE EXISTS (
                    SELECT 1 FROM organization_members m
                    JOIN users u ON u.id = m.user_id
                    WHERE m.organization_id = o.id
                      AND m.user_id = :userId AND m.active = true
                                            AND u.email_verified_at IS NOT NULL
                )
                """;
        fromWhere += " AND (CAST(:q AS text) IS NULL OR o.name ILIKE :q ESCAPE '\\' "
                + " OR o.email ILIKE :q ESCAPE '\\' OR o.phone ILIKE :q ESCAPE '\\' "
                + " OR o.default_timezone ILIKE :q ESCAPE '\\') "
                + " AND (CAST(:name AS text) IS NULL OR o.name ILIKE :name ESCAPE '\\') "
                + " AND (CAST(:email AS text) IS NULL OR o.email ILIKE :email ESCAPE '\\') "
                + " AND (CAST(:phone AS text) IS NULL OR o.phone ILIKE :phone ESCAPE '\\') "
                + " AND (CAST(:timezone AS text) IS NULL OR o.default_timezone ILIKE :timezone ESCAPE '\\') "
                + " AND (CAST(:status AS text) IS NULL OR o.registration_status = :status) "
                + " AND (CAST(:bookingEnabled AS boolean) IS NULL OR o.booking_enabled = :bookingEnabled) "
                + " AND (CAST(:createdFrom AS timestamptz) IS NULL OR o.created_at >= :createdFrom) "
                + " AND (CAST(:createdTo AS timestamptz) IS NULL OR o.created_at < :createdTo) ";

        String order = sortOrder(sort, Map.of(
                "id", "o.id", "name", "o.name", "email", "o.email", "phone", "o.phone",
                "timezone", "o.default_timezone", "bookingEnabled", "o.booking_enabled",
                "status", "o.registration_status", "createdAt", "o.created_at"), "o.id");
        return paged("""
                SELECT o.id, o.public_id, o.name, o.email, o.phone,
                       o.default_timezone, o.booking_enabled,
                       o.registration_status, o.created_at,
                       c.setup_status,
                       (SELECT m.role FROM organization_members m
                       WHERE m.organization_id = o.id AND m.user_id = :userId AND m.active = true
                       ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END
                       LIMIT 1) AS my_role
                """, fromWhere, order, params, page, size);
    }
}
