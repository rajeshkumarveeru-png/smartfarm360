package com.smartfarm360.platform.membership;

import com.smartfarm360.platform.rbac.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class MembershipDtos {
    private MembershipDtos() {}

    public record MemberResponse(long membershipId, long userId, String email, String displayName, List<String> roles, Instant joinedAt) {}

    public record UpdateRolesRequest(@NotEmpty @Size(max = 7) List<RoleCode> roles) {}

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotEmpty @Size(max = 7) List<RoleCode> roles) {}

    /**
     * {@code acceptUrl} is returned only when the invitation is created, so the inviter can also share it directly
     * (for example by phone). It only works for a signed-in user with the invited email address.
     */
    public record InvitationResponse(long id, String email, List<String> roles, Instant expiresAt, Instant createdAt, String acceptUrl) {}

    public record AcceptInvitationRequest(@NotBlank @Size(max = 200) String token) {}

    public record RoleResponse(String code, String name, List<String> permissions) {}
}
