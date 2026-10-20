package com.smartfarm360.platform.membership;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "sf_invitations")
@Getter @Setter @NoArgsConstructor
public class SfInvitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(nullable = false, length = 254) private String email;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "invited_by_user_id", nullable = false) private Long invitedByUserId;
    @Column(name = "accepted_by_user_id") private Long acceptedByUserId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "accepted_at") private Instant acceptedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sf_invitation_roles", joinColumns = @JoinColumn(name = "invitation_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<SfRole> roles = new HashSet<>();

    public boolean isPending(Instant now) { return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now); }
}
