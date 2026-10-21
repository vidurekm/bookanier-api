package online.bookanear.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import online.bookanear.api.config.OrganizationSecurity;
import online.bookanear.api.dto.BackofficeDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A pending request cannot change availability or existing reservations. */
@Service
public class AbsenceRequestService {
    public record CreateRequest(@NotNull Long locationId, @NotNull Long memberId, @NotNull LocalDate date,
                                @NotNull @Valid List<BackofficeDto.TimeInterval> intervals, String reason) {}
    private final NamedParameterJdbcTemplate db;
    private final OrganizationSecurity security;
    private final BackofficeService schedules;
    private final ObjectMapper mapper;

    public AbsenceRequestService(NamedParameterJdbcTemplate db, OrganizationSecurity security,
                                 BackofficeService schedules, ObjectMapper mapper) {
        this.db=db; this.security=security; this.schedules=schedules; this.mapper=mapper;
    }

    public Object list(long org, Authentication auth) {
        Long memberId=security.ownMemberId(org,auth);
        boolean manager=security.canWrite(org,auth);
        if (!manager && memberId==null) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Access denied");
        return db.queryForList("""
                SELECT r.id,r.member_id,r.location_id,r.work_date,r.requested_intervals::text AS intervals,
                       r.reason,r.status,r.created_at,r.decided_at,m.display_name AS member_name
                FROM member_absence_requests r JOIN organization_members m ON m.id=r.member_id
                WHERE r.organization_id=:org AND (CAST(:manager AS boolean) OR r.member_id=:member)
                ORDER BY r.created_at DESC LIMIT 150
                """,new MapSqlParameterSource().addValue("org",org).addValue("manager",manager).addValue("member",memberId));
    }

    @Transactional
    public Object create(long org, CreateRequest request, Authentication auth) {
        Long userId=security.authenticatedUserId(auth);
        if (userId==null || !security.canRead(org,auth))
            throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Access denied");
        if (request.date().isBefore(LocalDate.now()) || request.date().isAfter(LocalDate.now().plusDays(366)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid date");
        if (request.reason()!=null && request.reason().length()>1000)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Reason too long");
        if (request.intervals().size()>12)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Too many intervals");
        var assignment=db.queryForList("""
                SELECT 1 FROM member_locations ml JOIN organization_members m
                  ON ml.member_id=m.id AND ml.organization_id=m.organization_id
                WHERE ml.organization_id=:org AND ml.member_id=:member AND ml.location_id=:loc AND m.active
                """,Map.of("org",org,"member",request.memberId(),"loc",request.locationId()));
        if (assignment.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Member does not work at this location");
        if (!security.canWrite(org,auth) && !request.memberId().equals(security.ownMemberId(org,auth)))
            throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Only your own absences can be requested");
        // Reject malformed/overlapping time intervals before saving any request.
        java.time.LocalTime previous=null;
        var sorted=new java.util.ArrayList<>(request.intervals());
        if (sorted.contains(null)) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Null interval");
        sorted.sort(java.util.Comparator.comparing(BackofficeDto.TimeInterval::from,java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        for (var interval:sorted) {
            if (interval==null || interval.from()==null || interval.to()==null ||
                !interval.from().isBefore(interval.to()) || (previous!=null && interval.from().isBefore(previous)))
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid time intervals");
            previous=interval.to();
        }
        try {
            Long id=db.queryForObject("""
                    INSERT INTO member_absence_requests
                      (organization_id,member_id,location_id,work_date,requested_intervals,reason,requested_by)
                    VALUES (:org,:member,:loc,:day,CAST(:intervals AS jsonb),:reason,:user) RETURNING id
                    """,new MapSqlParameterSource().addValue("org",org).addValue("member",request.memberId())
                    .addValue("loc",request.locationId()).addValue("day",request.date())
                    .addValue("intervals",mapper.writeValueAsString(sorted))
                    .addValue("reason",request.reason()).addValue("user",userId),Long.class);
            if (security.canApproveOwnAbsence(org,request.memberId(),auth))
                return decide(org,id,true,auth);
            return Map.of("id",id,"status","pending");
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid intervals");
        }
    }

    @Transactional
    public Object decide(long org,long id,boolean approve,Authentication auth) {
        var rows=db.queryForList("""
                SELECT id,member_id,location_id,work_date,requested_intervals::text AS intervals,status
                FROM member_absence_requests WHERE organization_id=:org AND id=:id FOR UPDATE
                """,Map.of("org",org,"id",id));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Request not found");
        var row=rows.getFirst();
        long member=((Number)row.get("member_id")).longValue();
        if (!security.canWrite(org,auth) &&
            !(approve && security.canApproveOwnAbsence(org,member,auth)))
            throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Approval is not permitted");
        if (!"pending".equals(row.get("status")))
            throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","Request is already decided");
        if (approve) {
            try {
                var intervals=mapper.readValue((String)row.get("intervals"),
                        new TypeReference<List<BackofficeDto.TimeInterval>>(){});
                Object rawDate=row.get("work_date");
                LocalDate requestedDate=rawDate instanceof LocalDate ld ? ld : ((java.sql.Date) rawDate).toLocalDate();
                var day=new BackofficeDto.DatedScheduleDay(requestedDate,intervals);
                schedules.replaceDatedSchedule(org,((Number)row.get("location_id")).longValue(),member,
                        "working",new BackofficeDto.DatedSchedule(List.of(day)),false,false);
            } catch (JsonProcessingException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid request intervals");
            }
        }
        db.update("""
                UPDATE member_absence_requests SET status=:status,decided_by=:user,decided_at=now()
                WHERE organization_id=:org AND id=:id
                """,new MapSqlParameterSource().addValue("org",org).addValue("id",id)
                .addValue("status",approve?"approved":"rejected")
                .addValue("user",security.authenticatedUserId(auth)));
        return Map.of("id",id,"status",approve?"approved":"rejected");
    }
}
