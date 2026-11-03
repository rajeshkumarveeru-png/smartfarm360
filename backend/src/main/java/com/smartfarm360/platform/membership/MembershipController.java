package com.smartfarm360.platform.membership;

import com.smartfarm360.platform.membership.MembershipDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MembershipController {
    private final MembershipService service;

    public MembershipController(MembershipService service) { this.service = service; }

    @GetMapping("/roles")
    public List<RoleResponse> roles() { return service.roleCatalog(); }

    @GetMapping("/tenants/{tenantId}/members")
    @RequiresPermission(Permission.MEMBERS_READ)
    public List<MemberResponse> members(TenantContext ctx) { return service.members(ctx); }

    @PutMapping("/tenants/{tenantId}/members/{membershipId}/roles")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    public MemberResponse changeRoles(TenantContext ctx, @PathVariable long membershipId, @Valid @RequestBody UpdateRolesRequest request) {
        return service.changeRoles(ctx, membershipId, request.roles());
    }

    @DeleteMapping("/tenants/{tenantId}/members/{membershipId}")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(TenantContext ctx, @PathVariable long membershipId) { service.remove(ctx, membershipId); }

    @GetMapping("/tenants/{tenantId}/invitations")
    @RequiresPermission(Permission.MEMBERS_READ)
    public List<InvitationResponse> invitations(TenantContext ctx) { return service.pendingInvitations(ctx); }

    @PostMapping("/tenants/{tenantId}/invitations")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(TenantContext ctx, @Valid @RequestBody InviteRequest request) { return service.invite(ctx, request); }

    @DeleteMapping("/tenants/{tenantId}/invitations/{invitationId}")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(TenantContext ctx, @PathVariable long invitationId) { service.revokeInvitation(ctx, invitationId); }

    @PostMapping("/invitations/accept")
    public Map<String, Long> accept(CurrentUser user, @Valid @RequestBody AcceptInvitationRequest request) {
        return Map.of("tenantId", service.acceptInvitation(user, request.token()));
    }
}
