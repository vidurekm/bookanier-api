package online.bookanear.api.service;

import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * Prevent a member from being scheduled in two branches at the same instant.
 * Date-specific overrides replace the weekly template, including an empty (day-off) override.
 * Compare actual instants, not local clock times (branches can use different time zones).
 * Called inside the schedule-edit transaction, after the organization row is locked.
 */
@Component
public class MemberScheduleConflictValidator {
    private final NamedParameterJdbcTemplate db;

    public MemberScheduleConflictValidator(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    private record Weekly(long locationId, ZoneId zone, int weekday, LocalTime from, LocalTime to,
                          LocalDate validFrom, LocalDate validTo) {
        boolean applies(LocalDate date) {
            return date.getDayOfWeek().getValue() == weekday
                    && (validFrom == null || !date.isBefore(validFrom))
                    && (validTo == null || date.isBefore(validTo));
        }
    }
    private record BranchDate(long locationId, LocalDate date) {}
    private record ClockInterval(LocalTime from, LocalTime to) {}
    private record Shift(long locationId, LocalDate date, Instant from, Instant to) {}

    public void validate(long organizationId, long memberId) {
        var args = Map.of("org", organizationId, "member", memberId);
        List<Weekly> weekly = db.query("""
                SELECT s.location_id, l.timezone, s.weekday, s.start_time, s.end_time,
                       lower(s.effective_dates) AS valid_from,
                       upper(s.effective_dates) AS valid_to
                FROM schedule_rules s
                JOIN locations l ON l.id=s.location_id AND l.organization_id=s.organization_id
                WHERE s.organization_id=:org AND s.member_id=:member AND s.kind='working'
                """, args, (rs, n) -> new Weekly(
                rs.getLong("location_id"), ZoneId.of(rs.getString("timezone")),
                rs.getInt("weekday"), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class)));

        Map<BranchDate, List<ClockInterval>> overrides = new HashMap<>();
        Map<Long, ZoneId> zones = new HashMap<>();
        for (Weekly w : weekly) zones.put(w.locationId(), w.zone());
        db.query("""
                SELECT d.location_id, l.timezone, d.work_date, i.start_time, i.end_time
                FROM schedule_date_overrides d
                JOIN locations l ON l.id=d.location_id AND l.organization_id=d.organization_id
                LEFT JOIN schedule_date_override_intervals i ON i.override_id=d.id
                WHERE d.organization_id=:org AND d.member_id=:member AND d.kind='working'
                ORDER BY d.work_date, i.start_time
                """, args, rs -> {
            long location = rs.getLong("location_id");
            zones.put(location, ZoneId.of(rs.getString("timezone")));
            BranchDate key = new BranchDate(location, rs.getObject("work_date", LocalDate.class));
            List<ClockInterval> intervals = overrides.computeIfAbsent(key, ignored -> new ArrayList<>());
            LocalTime from = rs.getObject("start_time", LocalTime.class);
            if (from != null) intervals.add(new ClockInterval(from, rs.getObject("end_time", LocalTime.class)));
        });
        if (zones.size() < 2) return;

        // A weekly schedule repeats indefinitely. Check a full annual timezone/DST cycle
        // from today and from every future effective-date boundary; date overrides also
        // introduce change boundaries (a closed day must suppress the weekly template).
        LocalDate today = db.queryForObject("SELECT CURRENT_DATE", Map.of(), LocalDate.class);
        TreeSet<LocalDate> candidates = new TreeSet<>();
        addDates(candidates, today, 370, today);
        for (Weekly w : weekly) {
            if (w.validFrom() != null && w.validFrom().isAfter(today))
                addDates(candidates, w.validFrom(), 370, today);
            if (w.validTo() != null && w.validTo().isAfter(today))
                addDates(candidates, w.validTo().minusDays(7), 14, today);
        }
        for (BranchDate key : overrides.keySet()) {
            if (!key.date().isBefore(today))
                addDates(candidates, key.date().minusDays(2), 12, today);
        }

        // Include adjacent local days because different timezones can map to the
        // same UTC instant even when local dates differ.
        List<Shift> shifts = new ArrayList<>();
        for (LocalDate date : candidates) {
            for (var entry : zones.entrySet()) {
                long location = entry.getKey();
                List<ClockInterval> intervals = overrides.get(new BranchDate(location, date));
                if (intervals == null) {
                    intervals = new ArrayList<>();
                    for (Weekly w : weekly) {
                        if (w.locationId() == location && w.applies(date))
                            intervals.add(new ClockInterval(w.from(), w.to()));
                    }
                }
                for (ClockInterval interval : intervals) {
                    Instant from = date.atTime(interval.from()).atZone(entry.getValue()).toInstant();
                    Instant to = date.atTime(interval.to()).atZone(entry.getValue()).toInstant();
                    shifts.add(new Shift(location, date, from, to));
                }
            }
        }
        // Compare in UTC across dates as well: two locations in distant time zones
        // can overlap while their local calendars show different dates.
        shifts.sort(Comparator.comparing(Shift::from));
        for (int i = 0; i < shifts.size(); i++) {
            Shift first = shifts.get(i);
            for (int j = i + 1; j < shifts.size(); j++) {
                Shift second = shifts.get(j);
                if (!second.from().isBefore(first.to())) break;
                if (first.locationId() != second.locationId()
                        && second.from().isBefore(first.to())) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Employee " + memberId + " has overlapping shifts at locations "
                            + first.locationId() + " and " + second.locationId()
                            + " near " + first.date() + ". Change the working hours or date override.");
                }
            }
        }
    }

    private static void addDates(Set<LocalDate> dates, LocalDate start, int days, LocalDate today) {
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            if (!date.isBefore(today)) dates.add(date);
        }
    }
}
