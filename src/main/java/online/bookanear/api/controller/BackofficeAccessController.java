package online.bookanear.api.controller;

import online.bookanear.api.config.OrganizationSecurity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;

/** Current user's actual organization privileges, rather than assuming role from the UI. */
@RestController
@RequestMapping("/api/v1/backoffice/organizations/{org}/access")
public class BackofficeAccessController {
    private final OrganizationSecurity security;
    public BackofficeAccessController(OrganizationSecurity security) { this.security=security; }
    @GetMapping @PreAuthorize("@organizationSecurity.canRead(#p0, authentication)")
    public Object access(@PathVariable long org, Authentication auth) {
        var result=new LinkedHashMap<String,Object>();
        Long own=security.ownMemberId(org,auth);
        result.put("ownMemberId",own);
        result.put("canWrite",security.canWrite(org,auth));
        result.put("isOwner",security.isOwner(org,auth));
        result.put("canManageBookings",security.canManageBookings(org,auth));
        result.put("canApproveOwnAbsence",own!=null && security.canApproveOwnAbsence(org,own,auth));
        return result;
    }
}
