package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Two-step sign-in with an authenticator app (SaaS operations design, O6): required for platform administrators,
 * optional for everyone else. Ten single-use recovery codes are shown once when it is turned on.
 */
@Service
public class TwoStepService {
    static final int RECOVERY_CODES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient jdbc;
    private final SecretBox box;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final Clock clock;

    public TwoStepService(JdbcClient jdbc, SecretBox box, PasswordEncoder passwords, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.box = box;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    public record Setup(String secret, String otpauthUrl) {}

    public record RecoveryCodes(List<String> codes) {}

    /** Starts (or restarts) set-up: a new secret that is not used until a code from it is confirmed. */
    @Transactional
    public Setup setup(CurrentUser user, String password) {
        Map<String, Object> u = account(user.id());
        requirePassword(password, (String) u.get("password_hash"));
        if (enabled(user.id())) throw ApiException.conflict("TWO_STEP_ALREADY_ON", "Two-step sign-in is already on.");
        String secret = Totp.newSecret();
        jdbc.sql("""
                        insert into sf_user_mfa (user_id, secret_enc) values (:u, :s)
                        on conflict (user_id) do update set secret_enc = excluded.secret_enc, enabled_at = null, last_step = null, created_at = now()""")
                .param("u", user.id()).param("s", box.seal(secret)).update();
        return new Setup(secret, Totp.otpauthUrl(secret, (String) u.get("email")));
    }

    /** Confirms set-up with a code from the app and returns the recovery codes (shown once). */
    @Transactional
    public RecoveryCodes enable(CurrentUser user, String code) {
        Map<String, Object> row = jdbc.sql("select secret_enc, enabled_at from sf_user_mfa where user_id = :u").param("u", user.id()).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> ApiException.badRequest("TWO_STEP_NOT_STARTED", "Start setting up two-step sign-in first."));
        if (row.get("enabled_at") != null) throw ApiException.conflict("TWO_STEP_ALREADY_ON", "Two-step sign-in is already on.");
        long step = Totp.matchingStep(box.open((String) row.get("secret_enc")), code, clock.instant().getEpochSecond());
        if (step < 0) throw ApiException.badRequest("INVALID_TWO_STEP_CODE", "That code is not right. Check the time on your phone and try again.");
        jdbc.sql("update sf_user_mfa set enabled_at = :now, last_step = :s where user_id = :u")
                .param("now", Timestamp.from(clock.instant())).param("s", step).param("u", user.id()).update();
        audit.record(null, user.id(), "TWO_STEP_ENABLED", "USER", user.id(), Map.of());
        return new RecoveryCodes(newRecoveryCodes(user.id()));
    }

    /** Turns it off (not for platform administrators, who must keep it). */
    @Transactional
    public void disable(CurrentUser user, String password, String code) {
        Map<String, Object> u = account(user.id());
        requirePassword(password, (String) u.get("password_hash"));
        if (user.platformAdmin()) throw ApiException.forbidden("TWO_STEP_REQUIRED", "Platform administrators must keep two-step sign-in on.");
        if (!verify(user.id(), code, null)) throw ApiException.badRequest("INVALID_TWO_STEP_CODE", "That code is not right. Check the time on your phone and try again.");
        jdbc.sql("delete from sf_user_recovery_codes where user_id = :u").param("u", user.id()).update();
        jdbc.sql("delete from sf_user_mfa where user_id = :u").param("u", user.id()).update();
        audit.record(null, user.id(), "TWO_STEP_DISABLED", "USER", user.id(), Map.of());
    }

    public boolean enabled(long userId) {
        return jdbc.sql("select exists (select 1 from sf_user_mfa where user_id = :u and enabled_at is not null)").param("u", userId).query(Boolean.class).single();
    }

    /**
     * Checks a code from the app (each step only once, so a code seen over a shoulder cannot be replayed) or a recovery
     * code (each only once).
     */
    @Transactional
    public boolean verify(long userId, String code, String recoveryCode) {
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            return jdbc.sql("""
                            update sf_user_recovery_codes set used_at = :now
                            where id = (select id from sf_user_recovery_codes where user_id = :u and code_hash = :h and used_at is null limit 1)""")
                    .param("now", Timestamp.from(clock.instant())).param("u", userId).param("h", SecretTokens.hash(normalize(recoveryCode))).update() == 1;
        }
        Optional<Map<String, Object>> row = jdbc.sql("select secret_enc, last_step from sf_user_mfa where user_id = :u and enabled_at is not null for update")
                .param("u", userId).query().listOfRows().stream().findFirst();
        if (row.isEmpty()) return false;
        long step = Totp.matchingStep(box.open((String) row.get().get("secret_enc")), code, clock.instant().getEpochSecond());
        Object last = row.get().get("last_step");
        if (step < 0 || (last != null && step <= ((Number) last).longValue())) return false;
        jdbc.sql("update sf_user_mfa set last_step = :s where user_id = :u").param("s", step).param("u", userId).update();
        return true;
    }

    /** Replaces the recovery codes (the old ones stop working). */
    @Transactional
    public RecoveryCodes regenerate(CurrentUser user, String password) {
        requirePassword(password, (String) account(user.id()).get("password_hash"));
        if (!enabled(user.id())) throw ApiException.badRequest("TWO_STEP_NOT_STARTED", "Start setting up two-step sign-in first.");
        return new RecoveryCodes(newRecoveryCodes(user.id()));
    }

    private List<String> newRecoveryCodes(long userId) {
        jdbc.sql("delete from sf_user_recovery_codes where user_id = :u").param("u", userId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = String.format(Locale.ROOT, "%05d-%05d", RANDOM.nextInt(100_000), RANDOM.nextInt(100_000));
            codes.add(code);
            jdbc.sql("insert into sf_user_recovery_codes (user_id, code_hash) values (:u, :h)").param("u", userId).param("h", SecretTokens.hash(code)).update();
        }
        return codes;
    }

    private Map<String, Object> account(long userId) {
        return jdbc.sql("select email, password_hash from sf_users where id = :u").param("u", userId).query().singleRow();
    }

    private void requirePassword(String password, String hash) {
        if (password == null || !passwords.matches(password, hash)) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "The current password is incorrect.");
        }
    }

    private static String normalize(String code) { return code.trim().replace(" ", ""); }
}
