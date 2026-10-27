package online.bookanear.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.bookanear.api.dto.SetupDto;
import online.bookanear.api.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupDraftValidationTest {
    private final SetupService service = new SetupService(null, new ObjectMapper());

    private static SetupDto.Draft salon(String workerName, String serviceName) {
        return new SetupDto.Draft(
                "SALON", false,
                List.of(new SetupDto.LocationInput("Brno", "Hlavní 15", "Europe/Prague")),
                List.of(new SetupDto.MemberInput(workerName, List.of(0), true)),
                List.of(new SetupDto.ServiceInput(serviceName, 60, BigDecimal.ZERO, "CZK")),
                false, 1, 1, "Křeslo", false, true, false, 12, "09:00", "17:00", false, 0, null
        );
    }

    @Test
    void locationStepAllowsUnfilledTeamAndServices() {
        assertThatCode(() -> service.validate(salon("", ""), false)).doesNotThrowAnyException();
    }

    @Test
    void completeSetupRejectsUnfilledTeamOrService() {
        assertThatThrownBy(() -> service.validate(salon("", ""), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validate(salon("Anna", ""), true)).isInstanceOf(ApiException.class);
        var missingEmail = salon("Anna", "Stříhání");
        assertThatThrownBy(() -> service.validate(missingEmail, true)).isInstanceOf(ApiException.class);
        var withEmail = withMemberEmail(missingEmail, "anna@example.com");
        assertThatCode(() -> service.validate(withEmail, true)).doesNotThrowAnyException();
    }

    @Test
    void draftsCanOmitWorkerEmailsButCompletionRequiresAValidUniqueEmail() {
        assertThatCode(() -> service.validate(salon("Anna", "Stříhání"), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validate(withMemberEmail(salon("Anna", "Stříhání"), "anna@localhost"), true))
                .isInstanceOf(ApiException.class);
        var duplicated = withMemberEmail(salon("Anna", "Stříhání"), "ANNA@example.com");
        var duplicateMember = new SetupDto.MemberInput("Petr", List.of(0), true, false, false, "anna@example.com");
        var duplicateEmails = new SetupDto.Draft(duplicated.templateKey(), duplicated.multiLocation(),
                duplicated.locations(), List.of(duplicated.members().getFirst(), duplicateMember), duplicated.services(),
                duplicated.trackResources(), duplicated.resourceCount(), duplicated.resourceCapacity(),
                duplicated.resourceName(), duplicated.customerSelectResource(), duplicated.showStaffChoice(),
                duplicated.useWaitlist(), duplicated.eventCapacity(), duplicated.workingStart(), duplicated.workingEnd(),
                duplicated.ownerProvidesServices(), duplicated.ownerLocationIndex(), duplicated.openingHours());
        assertThatThrownBy(() -> service.validate(duplicateEmails, true)).isInstanceOf(ApiException.class);
    }

    @Test
    void workerCannotManageColleagueBookingsAndOwnBookingsAtTheSameTime() {
        var draft = salon("Anna", "Stříhání");
        var member = new SetupDto.MemberInput("Anna", List.of(0), true,
                true, true, false, "anna@example.com");
        var conflicting = new SetupDto.Draft(draft.templateKey(), draft.multiLocation(),
                draft.locations(), List.of(member), draft.services(), draft.trackResources(),
                draft.resourceCount(), draft.resourceCapacity(), draft.resourceName(),
                draft.customerSelectResource(), draft.showStaffChoice(), draft.useWaitlist(),
                draft.eventCapacity(), draft.workingStart(), draft.workingEnd(),
                draft.ownerProvidesServices(), draft.ownerLocationIndex(), draft.openingHours());
        assertThatThrownBy(() -> service.validate(conflicting, false))
                .isInstanceOf(ApiException.class);
    }

    private static SetupDto.Draft withMemberEmail(SetupDto.Draft draft, String email) {
        var old = draft.members().getFirst();
        var member = new SetupDto.MemberInput(old.name(), old.locationIndexes(), old.providesServices(),
                old.canManageBookings(), old.canManageOwnBookings(), old.canApproveOwnAbsence(), email);
        return new SetupDto.Draft(draft.templateKey(), draft.multiLocation(), draft.locations(),
                List.of(member), draft.services(), draft.trackResources(), draft.resourceCount(),
                draft.resourceCapacity(), draft.resourceName(), draft.customerSelectResource(),
                draft.showStaffChoice(), draft.useWaitlist(), draft.eventCapacity(), draft.workingStart(),
                draft.workingEnd(), draft.ownerProvidesServices(), draft.ownerLocationIndex(), draft.openingHours());
    }

    @Test
    void weeklyOpeningHoursAllowTwoSeparatedIntervalsAndClosedWeekend() {
        var old = salon("Anna", "Stříhání");
        var ok = withMemberEmail(new SetupDto.Draft(old.templateKey(), old.multiLocation(), old.locations(),
                old.members(), old.services(), old.trackResources(),old.resourceCount(),
                old.resourceCapacity(),old.resourceName(),old.customerSelectResource(),
                old.showStaffChoice(),old.useWaitlist(),old.eventCapacity(),old.workingStart(),old.workingEnd(),
                false,0,List.of(new SetupDto.OpeningIntervalInput(0,1,"09:00","12:00"),
                        new SetupDto.OpeningIntervalInput(0,1,"13:00","18:00"))), "anna@example.com");
        assertThatCode(() -> service.validate(ok, true)).doesNotThrowAnyException();
        var overlapping = new SetupDto.Draft(ok.templateKey(), ok.multiLocation(), ok.locations(),
                ok.members(),ok.services(),ok.trackResources(),ok.resourceCount(),ok.resourceCapacity(),
                ok.resourceName(),ok.customerSelectResource(),ok.showStaffChoice(),ok.useWaitlist(),
                ok.eventCapacity(),ok.workingStart(),ok.workingEnd(),false,0,
                List.of(new SetupDto.OpeningIntervalInput(0,1,"09:00","13:00"),
                        new SetupDto.OpeningIntervalInput(0,1,"12:00","17:00")));
        assertThatThrownBy(() -> service.validate(overlapping,true)).isInstanceOf(ApiException.class);
    }

    @Test
    void evenDraftRequiresLocationName() {
        SetupDto.Draft draft = salon("", "");
        var invalid = new SetupDto.Draft(draft.templateKey(), false,
                List.of(new SetupDto.LocationInput("", "Adresa", "Europe/Prague")),
                draft.members(), draft.services(), draft.trackResources(), draft.resourceCount(),
                draft.resourceCapacity(), draft.resourceName(), draft.customerSelectResource(),
                draft.showStaffChoice(), draft.useWaitlist(), draft.eventCapacity(), draft.workingStart(), draft.workingEnd(),
                draft.ownerProvidesServices(), draft.ownerLocationIndex(), draft.openingHours());
        assertThatThrownBy(() -> service.validate(invalid, false)).isInstanceOf(ApiException.class);
    }

    @Test
    void eventWithoutWorkersOrServicesIsAllowed() {
        SetupDto.Draft draft = new SetupDto.Draft("EVENTS", false,
                List.of(new SetupDto.LocationInput("Studio", "", "Europe/Prague")),
                List.of(), List.of(), false, 1, 1, "", false, false, false, 12, "09:00", "17:00", false, 0, null);
        assertThatCode(() -> service.validate(draft, true)).doesNotThrowAnyException();
    }
}
