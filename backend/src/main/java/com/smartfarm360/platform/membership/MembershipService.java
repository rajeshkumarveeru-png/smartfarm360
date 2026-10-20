package com.smartfarm360.platform.membership;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.auth.SecretTokens;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.membership.MembershipDtos.*;
import com.smartfarm360.platform.rbac.RoleCode;
import com.smartfarm360.platform.security.AuthProperties;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.SfTenant;
import com.smartfarm360.platform.tenant.SfTenantRepository;
import com.smartfarm360.platform.user.SfUserAccount;
import com.smartfarm360.platform.user.SfUserAccountRepository;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Members, roles and invitations. Rules:
 * <ul>
 *   <li>Only an owner can grant or remove the owner role.</li>
 *   <li>A farm business always keeps at least one active owner.</li>
 *   <li>Every referenced membership or invitation must belong to the current tenant (otherwise 404).</li>
 * </ul>
 */
@Service
public class MembershipService {
    private static final String OWNER = RoleCode.TENANT_OWNER.name();
    private final SfTenantMembershipRepository memberships;
    private final SfInvitationRepository invitations;
    private final SfRoleRepository roles;
    private final SfUserAccountRepository users;
    private final SfTenantRepository tenants;
    private final EmailSender emailSender;
    private final AuditService audit;
    private final AuthProperties properties;
    private final Clock clock;

    public MembershipService(SfTenantMembershipRepository memberships, SfInvitationRepository invitations, SfRoleRepository roles,
                             SfUserAccountRepository users, SfTenantRepository tenants, EmailSender emailSender,
                             AuditService audit, AuthProperties properties, Clock clock) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.roles = roles;
        this.users = users;
        this.tenants = tenants;
        this.emailSender = emailSender;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> roleCatalog() {
        return roles.findByTenantIdIsNullOrderById().stream()
                .map(r -> new RoleResponse(r.getCode(), r.getName(), r.getPermissions().stream().sorted().toList())).toList();
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> members(TenantContext ctx) {
        return memberships.findActiveByTenantId(ctx.tenantId()).stream().map(MembershipService::toResponse).toList();
    }

    @Transactional
    public MemberResponse changeRoles(TenantContext ctx, long membershipId, List<RoleCode> requested) {
        SfTenantMembership target = activeMembership(ctx, membershipId);
        Set<SfRole> newRoles = resolveRoles(requested);
        boolean ownerBefore = target.hasRole(OWNER);
        boolean ownerAfter = newRoles.stream().anyMatch(r -> r.getCode().equals(OWNER));
        if (ownerBefore != ownerAfter) requireOwner(ctx);
        if (ownerBefore && !ownerAfter) requireAnotherOwner(ctx);

        List<String> before = codes(target.getRoles());
        target.getRoles().clear();
        target.getRoles().addAll(newRoles);
        memberships.saveAndFlush(target);
        audit.record(ctx.tenantId(), ctx.userId(), "MEMBER_ROLES_CHANGED", "MEMBERSHIP", membershipId,
                Map.of("userId", target.getUser().getId(), "before", before, "after", codes(newRoles)));
        return toResponse(target);
    }

    /** Removes a member (or lets a member leave). The membership is deactivated, not deleted. */
    @Transactional
    public void remove(TenantContext ctx, long membershipId) {
        SfTenantMembership target = activeMembership(ctx, membershipId);
        if (target.hasRole(OWNER)) {
            requireOwner(ctx);
            requireAnotherOwner(ctx);
        }
        target.setActive(false);
        memberships.saveAndFlush(target);
        audit.record(ctx.tenantId(), ctx.userId(), "MEMBER_REMOVED", "MEMBERSHIP", membershipId, Map.of("userId", target.getUser().getId()));
    }

    @Transactional
    public InvitationResponse invite(TenantContext ctx, InviteRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Set<SfRole> inviteRoles = resolveRoles(request.roles());
        if (inviteRoles.stream().anyMatch(r -> r.getCode().equals(OWNER))) requireOwner(ctx);
        users.findByEmailIgnoreCase(email)
                .flatMap(u -> memberships.findByTenantIdAndUserId(ctx.tenantId(), u.getId()))
                .filter(SfTenantMembership::isActive)
                .ifPresent(m -> { throw ApiException.conflict("ALREADY_MEMBER", "This person is already a member."); });

        Instant now = clock.instant();
        invitations.findOpenForEmail(ctx.tenantId(), email).forEach(i -> i.setRevokedAt(now));
        String raw = SecretTokens.generate();
        SfInvitation invitation = new SfInvitation();
        invitation.setTenantId(ctx.tenantId());
        invitation.setEmail(email);
        invitation.setTokenHash(SecretTokens.hash(raw));
        invitation.setInvitedByUserId(ctx.userId());
        invitation.setExpiresAt(now.plus(properties.invitationTtl()));
        invitation.getRoles().addAll(inviteRoles);
        invitations.saveAndFlush(invitation);

        String url = properties.publicWebUrl() + "/accept-invitation#token=" + raw;
        SfTenant tenant = tenants.findById(ctx.tenantId()).orElseThrow();
        AfterCommit.run(() -> emailSender.sendInvitation(email, tenant.getName(), url, tenant.getPreferredLanguage()));
        audit.record(ctx.tenantId(), ctx.userId(), "INVITATION_CREATED", "INVITATION", invitation.getId(),
                Map.of("email", email, "roles", codes(inviteRoles)));
        return toResponse(invitation, url);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> pendingInvitations(TenantContext ctx) {
        return invitations.findPending(ctx.tenantId(), clock.instant()).stream().map(i -> toResponse(i, null)).toList();
    }

    @Transactional
    public void revokeInvitation(TenantContext ctx, long invitationId) {
        SfInvitation invitation = invitations.findByIdAndTenantId(invitationId, ctx.tenantId())
                .filter(i -> i.isPending(clock.instant()))
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found."));
        invitation.setRevokedAt(clock.instant());
        audit.record(ctx.tenantId(), ctx.userId(), "INVITATION_REVOKED", "INVITATION", invitationId, Map.of());
    }

    /** Joins the inviting tenant. The invitation must be pending and addressed to the signed-in user's email. */
    @Transactional
    public long acceptInvitation(CurrentUser user, String rawToken) {
        Instant now = clock.instant();
        SfUserAccount account = users.findById(user.id()).orElseThrow();
        SfInvitation invitation = invitations.findByTokenHash(SecretTokens.hash(rawToken))
                .filter(i -> i.isPending(now) && i.getEmail().equalsIgnoreCase(account.getEmail()))
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "This invitation is invalid, expired or for another account."));
        SfTenant tenant = tenants.findById(invitation.getTenantId()).orElseThrow();

        SfTenantMembership membership = memberships.findByTenantIdAndUserId(tenant.getId(), account.getId()).orElseGet(() -> {
            SfTenantMembership m = new SfTenantMembership();
            m.setTenant(tenant);
            m.setUser(account);
            m.setActive(false);
            return m;
        });
        if (membership.isActive()) throw ApiException.conflict("ALREADY_MEMBER", "You are already a member.");
        membership.setActive(true);
        membership.getRoles().clear();
        membership.getRoles().addAll(invitation.getRoles());
        memberships.saveAndFlush(membership);

        invitation.setAcceptedAt(now);
        invitation.setAcceptedByUserId(account.getId());
        audit.record(tenant.getId(), account.getId(), "INVITATION_ACCEPTED", "INVITATION", invitation.getId(),
                Map.of("membershipId", membership.getId()));
        return tenant.getId();
    }

    private SfTenantMembership activeMembership(TenantContext ctx, long membershipId) {
        return memberships.findByIdAndTenantId(membershipId, ctx.tenantId()).filter(SfTenantMembership::isActive)
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found."));
    }

    private static void requireOwner(TenantContext ctx) {
        if (!ctx.hasRole(OWNER)) throw ApiException.forbidden("OWNER_REQUIRED", "Only an owner can grant or remove the owner role.");
    }

    private void requireAnotherOwner(TenantContext ctx) {
        if (memberships.countActiveOwners(ctx.tenantId()) <= 1) {
            throw ApiException.conflict("LAST_OWNER", "A farm business must keep at least one owner.");
        }
    }

    private Set<SfRole> resolveRoles(List<RoleCode> requested) {
        Set<String> codes = new TreeSet<>();
        requested.forEach(r -> codes.add(r.name()));
        List<SfRole> found = roles.findByTenantIdIsNullAndCodeIn(codes);
        if (found.size() != codes.size()) throw ApiException.badRequest("INVALID_ROLE", "Unknown role.");
        return new HashSet<>(found);
    }

    private static List<String> codes(Collection<SfRole> roles) { return roles.stream().map(SfRole::getCode).sorted().toList(); }

    private static MemberResponse toResponse(SfTenantMembership m) {
        return new MemberResponse(m.getId(), m.getUser().getId(), m.getUser().getEmail(), m.getUser().getDisplayName(),
                codes(m.getRoles()), m.getCreatedAt());
    }

    private static InvitationResponse toResponse(SfInvitation i, String acceptUrl) {
        return new InvitationResponse(i.getId(), i.getEmail(), codes(i.getRoles()), i.getExpiresAt(), i.getCreatedAt(), acceptUrl);
    }
}
