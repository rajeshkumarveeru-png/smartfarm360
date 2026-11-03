package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.ratelimit.RateLimiter;
import com.smartfarm360.platform.security.AuthProperties;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Email verification (SaaS operations design, O5). New accounts can sign in at once, but create a farm business only
 * once the address is verified. Accepting an invitation sent to the address also verifies it.
 */
@Service
public class EmailVerificationService {
    static final Duration TTL = Duration.ofHours(48);
    private final JdbcClient jdbc;
    private final EmailSender email;
    private final AuthProperties properties;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final Clock clock;

    public EmailVerificationService(JdbcClient jdbc, EmailSender email, AuthProperties properties, RateLimiter rateLimiter, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.email = email;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
    }

    /** Sends a new link; earlier links stop working. */
    @Transactional
    public void issue(long userId, String address, String language) {
        Instant now = clock.instant();
        jdbc.sql("update sf_email_verification_tokens set used_at = :now where user_id = :u and used_at is null")
                .param("now", Timestamp.from(now)).param("u", userId).update();
        String raw = SecretTokens.generate();
        jdbc.sql("insert into sf_email_verification_tokens (user_id, token_hash, expires_at) values (:u, :h, :e)")
                .param("u", userId).param("h", SecretTokens.hash(raw)).param("e", Timestamp.from(now.plus(TTL))).update();
        // The token is in the URL fragment so it is not sent to servers or written to access logs.
        String url = properties.publicWebUrl() + "/verify-email#token=" + raw;
        AfterCommit.run(() -> email.sendNotice(address, language, EmailSender.Notice.VERIFY_EMAIL, Map.of("url", url)));
    }

    @Transactional
    public void verify(String rawToken) {
        Instant now = clock.instant();
        Optional<Long> userId = jdbc.sql("""
                        update sf_email_verification_tokens set used_at = :now
                        where token_hash = :h and used_at is null and expires_at > :now returning user_id""")
                .param("now", Timestamp.from(now)).param("h", SecretTokens.hash(rawToken == null ? "" : rawToken)).query(Long.class).optional();
        long id = userId.orElseThrow(() -> ApiException.badRequest("INVALID_VERIFICATION_TOKEN", "This verification link is invalid or has expired."));
        markVerified(id);
        audit.record(null, id, "EMAIL_VERIFIED", "USER", id, Map.of());
    }

    @Transactional
    public void resend(CurrentUser user) {
        rateLimiter.check("verify:user:" + user.id(), 3, Duration.ofHours(1));
        Map<String, Object> row = jdbc.sql("select email, preferred_language, email_verified_at from sf_users where id = :u")
                .param("u", user.id()).query().singleRow();
        if (row.get("email_verified_at") != null) throw ApiException.conflict("EMAIL_ALREADY_VERIFIED", "This email address is already verified.");
        issue(user.id(), (String) row.get("email"), (String) row.get("preferred_language"));
    }

    /** Also used when an invitation sent to the address is accepted, and for the bootstrap administrator. */
    public void markVerified(long userId) {
        jdbc.sql("update sf_users set email_verified_at = coalesce(email_verified_at, :now) where id = :u")
                .param("now", Timestamp.from(clock.instant())).param("u", userId).update();
    }

    public boolean isVerified(long userId) {
        return jdbc.sql("select email_verified_at is not null from sf_users where id = :u").param("u", userId).query(Boolean.class).single();
    }

    /** Refuses actions that need a verified address (creating a farm business). */
    public void requireVerified(long userId) {
        if (!isVerified(userId)) throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Verify your email address first. We sent you a link.");
    }
}
