package online.bookanear.api.service;

import online.bookanear.api.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CatalogAssignmentsService {
    private final NamedParameterJdbcTemplate db;
    private final StaffResourceService staffResourceService;
    public CatalogAssignmentsService(NamedParameterJdbcTemplate db, StaffResourceService staffResourceService) {
        this.db=db;
        this.staffResourceService=staffResourceService;
    }
    private void ensure(long org,String table,long id) {
        // Table identifiers are only fixed internal literals.
        if (!List.of("locations","organization_members","services").contains(table)) throw new IllegalArgumentException();
        var rows=db.queryForList("SELECT id FROM "+table+" WHERE organization_id=:org AND id=:id",
                Map.of("org",org,"id",id));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Object not found");
    }
    public List<Map<String,Object>> locationServices(long org,long loc) {
        ensure(org,"locations",loc);
        return db.queryForList("""
                SELECT s.id AS service_id,s.name,COALESCE(ls.active,false) AS active
                FROM services s LEFT JOIN location_services ls
                  ON ls.service_id=s.id AND ls.location_id=:loc AND ls.organization_id=:org
                WHERE s.organization_id=:org AND s.active=true ORDER BY s.name
                """,Map.of("org",org,"loc",loc));
    }
    @Transactional
    public List<Map<String,Object>> setLocationServices(long org,long loc,List<Long> ids) {
        ensure(org,"locations",loc);
        var wanted=new HashSet<>(ids);
        for(long id:wanted) ensure(org,"services",id);
        db.update("UPDATE location_services SET active=false WHERE organization_id=:org AND location_id=:loc",
                Map.of("org",org,"loc",loc));
        for(long id:wanted) db.update("""
                INSERT INTO location_services(organization_id,location_id,service_id,active)
                VALUES(:org,:loc,:svc,true) ON CONFLICT(location_id,service_id) DO UPDATE SET active=true
                """,Map.of("org",org,"loc",loc,"svc",id));
        return locationServices(org,loc);
    }
    public List<Long> memberLocations(long org,long member) {
        ensure(org,"organization_members",member);
        return db.queryForList("""
                SELECT location_id FROM member_locations WHERE organization_id=:org AND member_id=:member
                ORDER BY location_id
                """,Map.of("org",org,"member",member),Long.class);
    }
    @Transactional
    public List<Long> setMemberServices(long org,long member,List<Long> ids) {
        ensure(org,"organization_members",member);
        var wanted=new HashSet<>(ids);
        for(long id:wanted) ensure(org,"services",id);
        db.update("UPDATE worker_services SET active=false WHERE organization_id=:org AND member_id=:member",
                Map.of("org",org,"member",member));
        for(long id:wanted) db.update("""
                INSERT INTO worker_services(organization_id,member_id,service_id,active)
                VALUES(:org,:member,:svc,true)
                ON CONFLICT(member_id,service_id) DO UPDATE SET active=true
                """,Map.of("org",org,"member",member,"svc",id));
        return wanted.stream().sorted().toList();
    }
    @Transactional
    public List<Long> setMemberLocations(long org,long member,List<Long> ids) {
        ensure(org,"organization_members",member);
        var wanted=new HashSet<>(ids);
        for(long id:wanted) ensure(org,"locations",id);
        var old=new HashSet<>(memberLocations(org,member));
        for(long id:wanted) if(!old.contains(id)) db.update("""
                INSERT INTO member_locations(organization_id,member_id,location_id)
                VALUES(:org,:member,:loc)
                """,Map.of("org",org,"member",member,"loc",id));
        for(long id:old) if(!wanted.contains(id)) {
            Long inUse=db.queryForObject("""
                    SELECT (SELECT count(*) FROM schedule_rules sr WHERE sr.organization_id=:org
                              AND sr.location_id=:loc AND sr.member_id=:member
                              AND (upper_inf(sr.effective_dates) OR upper(sr.effective_dates)>CURRENT_DATE))
                    """,Map.of("org",org,"loc",id,"member",member),Long.class);
            if(inUse!=null && inUse>0) throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot remove location while working schedules exist");
            // Historic reservations may prevent deletion via FK; surface as 409.
            try { db.update("""
                    DELETE FROM member_locations WHERE organization_id=:org AND member_id=:member AND location_id=:loc
                    """,Map.of("org",org,"member",member,"loc",id)); }
            catch(DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT,"Cannot remove a location used by reservations or schedules");
            }
        }
        staffResourceService.synchronizeMember(org, member);
        return memberLocations(org,member);
    }
}
