package online.bookanear.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.dto.SetupDto;
import online.bookanear.api.dto.InvitationDto;
import online.bookanear.api.dto.EmailRules;
import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.*;

@Service
public class SetupService {
    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper mapper;
    private final InvitationService invitationService;
    @Autowired
    public SetupService(NamedParameterJdbcTemplate db, ObjectMapper mapper, InvitationService invitationService) {
        this.db=db; this.mapper=mapper; this.invitationService=invitationService;
    }
    public SetupService(NamedParameterJdbcTemplate db, ObjectMapper mapper) {
        this(db, mapper, null);
    }

    public Object templates() {
        return db.queryForList("SELECT template_key,booking_model,label,steps::text AS steps,defaults::text AS defaults FROM business_templates WHERE active ORDER BY CASE template_key WHEN 'SALON' THEN 0 WHEN 'RESTAURANT' THEN 1 WHEN 'RENTAL' THEN 2 WHEN 'EVENTS' THEN 3 ELSE 4 END", Map.of());
    }
    public Object config(long org) {
        var rows=db.queryForList("SELECT c.organization_id,c.template_key,c.booking_model,c.setup_status,c.settings::text AS settings,c.locked_at FROM organization_booking_config c WHERE organization_id=:org", Map.of("org",org));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Configuration missing");
        return rows.getFirst();
    }
    public Object owner(long org) {
        return db.queryForList("""
                SELECT id,display_name AS name FROM organization_members
                WHERE organization_id=:org AND role='owner' AND active=true AND user_id IS NOT NULL
                ORDER BY id LIMIT 1
                """, Map.of("org",org)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,"SETUP_OWNER_MISSING","Owner not found"));
    }

    private static List<SetupDto.OpeningIntervalInput> openingHours(SetupDto.Draft draft) {
        if (draft.openingHours() != null) return draft.openingHours();
        // Existing drafts serialized before weekly opening hours were introduced.
        var old = new ArrayList<SetupDto.OpeningIntervalInput>();
        if (draft.locations() != null) for (int li=0; li<draft.locations().size(); li++)
            for (int d=1; d<=5; d++) old.add(new SetupDto.OpeningIntervalInput(
                    li,d,draft.workingStart(),draft.workingEnd()));
        return old;
    }

    private static boolean validCoordinates(Double latitude, Double longitude) {
        return (latitude == null && longitude == null) ||
            (latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180);
    }

    private static boolean offersServices(SetupDto.MemberInput member) {
        return !Boolean.FALSE.equals(member.providesServices());
    }

    private static String preferredLanguage(String language) {
        if (language == null || language.isBlank()) return "cs";
        if (!java.util.Set.of("cs","pl","sk","en").contains(language))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unsupported public language");
        return language;
    }
    private static String preferredCurrency(String currency) {
        if (currency == null || currency.isBlank()) return "CZK";
        if (!java.util.Set.of("CZK","PLN","EUR").contains(currency))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unsupported currency");
        return currency;
    }
    private static String dateFormat(String format) {
        if (format == null || format.isBlank()) return "DD.MM.YYYY";
        if (!java.util.Set.of("DD.MM.YYYY", "DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD").contains(format))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unsupported date format");
        return format;
    }
    private String serialize(SetupDto.Draft draft) {
        try { return mapper.writeValueAsString(draft); }
        catch(JsonProcessingException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid setup"); }
    }
    private String model(String key) {
        var rows=db.queryForList("SELECT booking_model FROM business_templates WHERE template_key=:key AND active",Map.of("key",key));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unknown template");
        return (String)rows.getFirst().get("booking_model");
    }
    private String resourceTypeCode(long typeId) {
        return db.queryForObject("SELECT code FROM resource_types WHERE id=:id", Map.of("id",typeId),String.class);
    }
    void validate(SetupDto.Draft draft, boolean finalSubmission) {
        preferredLanguage(draft.defaultPublicLanguage());
        preferredCurrency(draft.defaultCurrency());
        dateFormat(draft.dateFormat());
        if(draft.locations()==null || draft.locations().isEmpty() || draft.locations().size()>30)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","At least one location required");
        if(!draft.multiLocation() && draft.locations().size()!=1)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Single branch must have one location");
        if(draft.members()!=null && draft.members().size()>100) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Too many members");
        if(draft.services()!=null && draft.services().size()>100) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Too many services");
        for(var loc:draft.locations()) if(loc==null || loc.name()==null || loc.name().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid location name");
        for (var loc:draft.locations()) if (!validCoordinates(loc.latitude(), loc.longitude()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid location coordinates");
        // A draft is saved after each wizard step. Later steps may still contain
        // empty placeholders (e.g. member name or service name), which must NOT
        // prevent saving the location step. Final submission remains strict.
        if(draft.members()!=null) for(var m:draft.members()) {
            if(m==null || m.name()==null || (finalSubmission && m.name().isBlank()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid worker name");
            if(finalSubmission && (m.email()==null || !m.email().trim().matches(EmailRules.WITH_DOMAIN_SUFFIX_OR_EMPTY)
                    || m.email().isBlank()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","A valid email is required for every team member");
            if(m.locationIndexes()!=null) for(var i:m.locationIndexes()) if(i==null || i<0 || i>=draft.locations().size())
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid worker location");
        }
        if (finalSubmission && draft.members() != null) {
            var emails = new HashSet<String>();
            for (var member : draft.members()) {
                if (!emails.add(member.email().trim().toLowerCase(Locale.ROOT)))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Each team member must have a unique email");
            }
        }
        if(draft.services()!=null) for(var service:draft.services()) {
            if(service==null || service.name()==null || (finalSubmission && service.name().isBlank()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid service");
            // In-progress placeholder services have no name yet; validate their
            // other fields when they become real services or on final submit.
            if((finalSubmission || !service.name().isBlank()) &&
               (service.durationMinutes()<5 || service.durationMinutes()>1440 ||
                service.price()==null || service.price().signum()<0 || service.currency()==null || !java.util.Set.of("CZK","PLN","EUR").contains(service.currency())))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid service");
        }
        if(draft.trackResources() && (draft.resourceCount()<1 || draft.resourceCapacity()<1))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Resource count and capacity required");
        if(draft.ownerProvidesServices() && (draft.ownerLocationIndex()<0 || draft.ownerLocationIndex()>=draft.locations().size()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid owner location");
        // Validate each branch/day independently: multiple intervals are allowed, but not overlaps.
        var byDay = new HashMap<String,List<SetupDto.OpeningIntervalInput>>();
        for (var interval : openingHours(draft)) {
            if (interval == null || interval.locationIndex()<0 || interval.locationIndex()>=draft.locations().size()
                    || interval.weekday()<1 || interval.weekday()>7)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid opening hours location/day");
            try {
                if (!LocalTime.parse(interval.start()).isBefore(LocalTime.parse(interval.end()))) throw new IllegalArgumentException();
            } catch (Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid opening hours interval"); }
            byDay.computeIfAbsent(interval.locationIndex()+":"+interval.weekday(), ignored->new ArrayList<>()).add(interval);
        }
        for (var entry : byDay.values()) {
            entry.sort(Comparator.comparing(SetupDto.OpeningIntervalInput::start));
            for (int i=1; i<entry.size(); i++)
                if (LocalTime.parse(entry.get(i).start()).isBefore(LocalTime.parse(entry.get(i-1).end())))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Overlapping opening hours");
        }
    }
    @Transactional
    public Object save(long org, SetupDto.Draft draft) {
        model(draft.templateKey()); validate(draft, false);
        var rows=db.queryForList("SELECT setup_status FROM organization_booking_config WHERE organization_id=:org FOR UPDATE",Map.of("org",org));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Configuration missing");
        if("complete".equals(rows.getFirst().get("setup_status")))
            throw new ApiException(HttpStatus.CONFLICT,"SETUP_LOCKED","Booking model is locked");
        db.update("UPDATE organization_booking_config SET template_key=:key,booking_model=:model,settings=CAST(:settings AS jsonb),updated_at=now() WHERE organization_id=:org",
            Map.of("org",org,"key",draft.templateKey(),"model",model(draft.templateKey()),"settings",serialize(draft)));
        return config(org);
    }
    @Transactional
    public Object updateOptions(long org, SetupDto.Options options) {
        var current=db.queryForList("SELECT setup_status,booking_model FROM organization_booking_config WHERE organization_id=:org FOR UPDATE",Map.of("org",org));
        if(current.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Configuration missing");
        if(!"complete".equals(current.getFirst().get("setup_status")))
            throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","Finish setup first");
        Long resources=db.queryForObject("SELECT count(*) FROM resources WHERE organization_id=:org AND active",Map.of("org",org),Long.class);
        if(options.customerSelectResource() && (resources==null || resources==0))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","No resources to select");
        var settingArgs=new MapSqlParameterSource().addValue("org",org)
                .addValue("select",options.customerSelectResource())
                .addValue("staff",options.showStaffChoice())
                .addValue("capacity",options.eventCapacity())
                .addValue("language",preferredLanguage(options.defaultPublicLanguage()))
                .addValue("currency",preferredCurrency(options.defaultCurrency()))
                .addValue("dateFormat",dateFormat(options.dateFormat()))
                .addValue("advanceDays",options.maxAdvanceDays(),java.sql.Types.INTEGER);
        db.update("""
            UPDATE organization_booking_config SET settings=settings || jsonb_build_object(
              'customerSelectResource',CAST(:select AS boolean),
              'showStaffChoice',CAST(:staff AS boolean),
              'eventCapacity',CAST(:capacity AS integer),
              'defaultPublicLanguage',CAST(:language AS text),
              'defaultCurrency',CAST(:currency AS text),
              'dateFormat',CAST(:dateFormat AS text),
              'maxAdvanceDays',CAST(COALESCE(:advanceDays,COALESCE((settings->>'maxAdvanceDays')::integer,180)) AS integer)),updated_at=now()
            WHERE organization_id=:org
            """,settingArgs);
        return config(org);
    }
    @Transactional
    public Object finish(long org, SetupDto.Draft draft) {
        // Never complete onboarding with blank worker/service placeholders.
        validate(draft, true);
        save(org,draft);
        var rows=db.queryForList("SELECT * FROM organization_booking_config WHERE organization_id=:org FOR UPDATE",Map.of("org",org));
        if(rows.isEmpty() || "complete".equals(rows.getFirst().get("setup_status")))
            throw new ApiException(HttpStatus.CONFLICT,"SETUP_LOCKED","Already configured");
        String model=model(draft.templateKey());
        if(("RESOURCE".equals(model) || "RENTAL".equals(model)) && !draft.trackResources())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Resources are required for this booking model");
        if(!"EVENT".equals(model) && (draft.services()==null || draft.services().isEmpty()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","At least one service is required");
        if("APPOINTMENT".equals(model) && !draft.ownerProvidesServices()
                && (draft.members()==null || draft.members().stream().noneMatch(SetupService::offersServices)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","At least one bookable worker is required; enable the owner or a worker");
        // Existing organizations keep their catalog and bookings; only the mode is confirmed.
        Long existing=db.queryForObject("SELECT count(*) FROM locations WHERE organization_id=:org",Map.of("org",org),Long.class);
        if(existing!=null && existing>0) {
            db.update("UPDATE organization_booking_config SET setup_status='complete',locked_at=now(),updated_at=now() WHERE organization_id=:org",Map.of("org",org));
            return config(org);
        }
        var locIds=new ArrayList<Long>();
        var intervals=openingHours(draft);
        for(int li=0;li<draft.locations().size();li++) {
            var loc=draft.locations().get(li);
            String tz=loc.timezone()==null || loc.timezone().isBlank()?"Europe/Prague":loc.timezone();
            try { java.time.ZoneId.of(tz); } catch(Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unknown timezone"); }
            Long id=db.queryForObject("INSERT INTO locations(organization_id,name,address,timezone,latitude,longitude) VALUES(:org,:name,:address,:tz,:latitude,:longitude) RETURNING id",
                new MapSqlParameterSource().addValue("org",org).addValue("name",loc.name().trim()).addValue("address",loc.address()).addValue("tz",tz).addValue("latitude",loc.latitude()).addValue("longitude",loc.longitude()),Long.class);
            locIds.add(id);
            for(var slot:intervals) if(slot.locationIndex()==li) {
                db.update("INSERT INTO schedule_rules(organization_id,location_id,kind,weekday,start_time,end_time) VALUES(:org,:loc,'open',:day,CAST(:start AS time),CAST(:end AS time))",
                    Map.of("org",org,"loc",id,"day",slot.weekday(),"start",slot.start(),"end",slot.end()));
            }
        }
        var memberIds=new ArrayList<Long>(); // reservable people ONLY
        var memberBranches=new HashMap<Long,List<Integer>>();
        var invitationMembers=new ArrayList<Map.Entry<Long,SetupDto.MemberInput>>();
        if(draft.members()!=null) for(var m:draft.members()) {
            Long mid=db.queryForObject("INSERT INTO organization_members(organization_id,display_name,role,can_manage_bookings,can_approve_own_absence) VALUES(:org,:name,'worker',:manage,:approve) RETURNING id",
                Map.of("org",org,"name",m.name().trim(),"manage",Boolean.TRUE.equals(m.canManageBookings()),"approve",Boolean.TRUE.equals(m.canApproveOwnAbsence())),Long.class);
            invitationMembers.add(Map.entry(mid,m));
            List<Integer> selected = new ArrayList<>();
            // One primary branch at onboarding: avoid assigning identical worker shifts
            // simultaneously to multiple locations. Additional branches get their own
            // per-day schedule later in the backoffice.
            int initialBranch = m.locationIndexes()!=null && !m.locationIndexes().isEmpty()
                    ? m.locationIndexes().getFirst() : 0;
            for(int li=0;li<locIds.size();li++) {
                if(li!=initialBranch) continue;
                selected.add(li);
                db.update("INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(:org,:mid,:loc)",Map.of("org",org,"mid",mid,"loc",locIds.get(li)));
            }
            if(offersServices(m) && "APPOINTMENT".equals(model)) {
                memberIds.add(mid);
                memberBranches.put(mid,selected);
            }
        }
        if(draft.ownerProvidesServices() && "APPOINTMENT".equals(model)) {
            long ownerId=((Number)((Map<?,?>)owner(org)).get("id")).longValue();
            int li=draft.ownerLocationIndex();
            db.update("INSERT INTO member_locations(organization_id,member_id,location_id) VALUES(:org,:mid,:loc) ON CONFLICT(member_id,location_id) DO NOTHING",
                Map.of("org",org,"mid",ownerId,"loc",locIds.get(li)));
            memberIds.add(ownerId);
            memberBranches.put(ownerId,List.of(li));
        }
        Long equipmentType=null;
        Long staffType=null;
        if("APPOINTMENT".equals(model) && !memberIds.isEmpty()) {
            staffType=db.queryForObject("INSERT INTO resource_types(organization_id,name,category,code) VALUES(:org,'Staff member','staff','staff') ON CONFLICT(organization_id,code) DO UPDATE SET category='staff' RETURNING id",Map.of("org",org),Long.class);
            for(var mid:memberIds) for(var li:memberBranches.get(mid)) {
                var loc=locIds.get(li);
                // Staff working intervals mirror the location's opening intervals for now.
                // Individual shifts can be adjusted in the team schedule later.
                for(var slot:intervals) if(slot.locationIndex()==li) {
                    db.update("INSERT INTO schedule_rules(organization_id,location_id,member_id,kind,weekday,start_time,end_time) VALUES(:org,:loc,:mid,'working',:day,CAST(:start AS time),CAST(:end AS time))",
                        Map.of("org",org,"loc",loc,"mid",mid,"day",slot.weekday(),"start",slot.start(),"end",slot.end()));
                }
                db.update("INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,capacity,member_id) SELECT :org,:loc,m.display_name,'staff',:type,1,m.id FROM organization_members m WHERE m.organization_id=:org AND m.id=:mid",
                    Map.of("org",org,"loc",loc,"type",staffType,"mid",mid));
            }
        }
        if(draft.trackResources()) {
            String name=draft.resourceName()==null || draft.resourceName().isBlank()?"Equipment":draft.resourceName().trim();
            String category="RENTAL".equals(model)?"equipment":"space";
            // Template choice identifies standard type independently of the localized display name.
            // Changing the resource name never affects its stable machine code.
            String resourceCode = switch (draft.templateKey()) {
                case "RESTAURANT" -> "table";
                case "RENTAL" -> "equipment";
                case "SALON" -> "chair";
                default -> null;
            };
            Long type = resourceCode == null
                ? db.queryForObject("INSERT INTO resource_types(organization_id,name,category) VALUES(:org,:name,:category) RETURNING id",
                    Map.of("org",org,"name",name,"category",category),Long.class)
                : db.queryForObject("INSERT INTO resource_types(organization_id,name,category,code) VALUES(:org,:name,:category,:code) ON CONFLICT(organization_id,code) DO UPDATE SET category=EXCLUDED.category RETURNING id",
                    Map.of("org",org,"name",name,"category",category,"code",resourceCode),Long.class);
            equipmentType=type;
            for(var loc:locIds) for(int i=1;i<=draft.resourceCount();i++) {
                db.update("INSERT INTO resources(organization_id,location_id,name,resource_type,resource_type_id,capacity,inventory_code) VALUES(:org,:loc,:name,:type,:tid,:capacity,:code)",
                    new MapSqlParameterSource().addValue("org",org).addValue("loc",loc).addValue("name",name+" #"+i).addValue("type",resourceCode==null?resourceTypeCode(type):resourceCode).addValue("tid",type).addValue("capacity",draft.resourceCapacity()).addValue("code",null));
            }
        }
        if(!"EVENT".equals(model) && draft.services()!=null) for(var s:draft.services()) {
            Long sid=db.queryForObject("INSERT INTO services(organization_id,name) VALUES(:org,:name) RETURNING id",Map.of("org",org,"name",s.name().trim()),Long.class);
            db.update("INSERT INTO service_versions(service_id,valid_during,duration_minutes,slot_interval_minutes,price,currency,duration_mode,max_duration_minutes) VALUES(:sid,daterange(current_date,NULL,'[)'),:duration,15,:price,:currency,:mode,:max)",
                new MapSqlParameterSource().addValue("sid",sid).addValue("duration",s.durationMinutes()).addValue("price",s.price()).addValue("currency",s.currency().trim().toUpperCase(Locale.ROOT)).addValue("mode","RENTAL".equals(model)?"CUSTOM":"FIXED").addValue("max","RENTAL".equals(model)?43200:null));
            if(staffType!=null) db.update("INSERT INTO service_resource_requirements(service_id,resource_type,resource_type_id,quantity,min_capacity) VALUES(:sid,'staff',:type,1,NULL)",Map.of("sid",sid,"type",staffType));
            if(equipmentType!=null) db.update("INSERT INTO service_resource_requirements(service_id,resource_type,resource_type_id,quantity,min_capacity) VALUES(:sid,:code,:type,1,NULL)",Map.of("sid",sid,"code",resourceTypeCode(equipmentType),"type",equipmentType));
            for(var loc:locIds) db.update("INSERT INTO location_services(organization_id,location_id,service_id) VALUES(:org,:loc,:sid)",Map.of("org",org,"loc",loc,"sid",sid));
            for(var mid:memberIds) db.update("INSERT INTO worker_services(organization_id,member_id,service_id) VALUES(:org,:mid,:sid)",Map.of("org",org,"mid",mid,"sid",sid));
        }
        db.update("UPDATE organization_booking_config SET setup_status='complete',locked_at=now(),updated_at=now() WHERE organization_id=:org",Map.of("org",org));
        if (!invitationMembers.isEmpty()) {
            if (invitationService == null) throw new IllegalStateException("Invitation service is required to finish onboarding");
            for (var entry : invitationMembers) {
                var member = entry.getValue();
                invitationService.create(org, new InvitationDto.CreateRequest(
                        member.email(), member.name(), "worker", member.canManageBookings(),
                        member.canApproveOwnAbsence(), entry.getKey()));
            }
        }
        return config(org);
    }
}
