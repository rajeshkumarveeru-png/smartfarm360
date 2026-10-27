package com.smartfarm360.platform.privacy;

import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.auth.SfRefreshToken;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Terms and privacy notice, consent, a copy of one's own data, and deleting one's account (SaaS operations design,
 * sections 8 and 11, O11, O12). Legal texts are written by the product owner and published from the platform console.
 */
@Service
public class PrivacyService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final EmailSender email;
    private final Clock clock;

    public PrivacyService(JdbcClient jdbc, PasswordEncoder passwords, AuditService audit, PlatformAudit platformAudit, EmailSender email, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.email = email;
        this.clock = clock;
    }

    // ---- Legal documents (O12) -------------------------------------------------------------------------------------------

    public record LegalDocument(String kind, int version, String language, String title, String body, String changes, Instant publishedAt) {}

    public record LegalText(String language, String title, String body) {}

    public record PublishRequest(String kind, List<LegalText> texts, String changes) {}

    /** The latest terms and privacy notice in the language asked for (English when there is no Tamil text). */
    @Transactional(readOnly = true)
    public List<LegalDocument> current(String language) {
        String lang = "ta".equals(language) ? "ta" : "en";
        List<LegalDocument> out = new ArrayList<>();
        for (String kind : List.of("TERMS", "PRIVACY")) {
            jdbc.sql("""
                            select kind, version, language, title, body, changes, published_at from sf_legal_documents
                            where kind = :k and version = (select max(version) from sf_legal_documents where kind = :k)
                            order by case when language = :l then 0 else 1 end limit 1""")
                    .param("k", kind).param("l", lang).query((rs, i) -> doc(rs)).optional().ifPresent(out::add);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LegalDocument> allDocuments() {
        return jdbc.sql("select kind, version, language, title, body, changes, published_at from sf_legal_documents order by kind, version desc, language")
                .query((rs, i) -> doc(rs)).list();
    }

    /** Publishes a new version of the terms or the privacy notice, in English and Tamil at once. Everyone accepts it again. */
    @Transactional
    public List<LegalDocument> publish(CurrentUser admin, PublishRequest r) {
        if (!List.of("TERMS", "PRIVACY").contains(r.kind())) throw ApiException.badRequest("UNKNOWN_DOCUMENT", "Unknown document.");
        Map<String, LegalText> byLang = new LinkedHashMap<>();
        if (r.texts() != null) for (LegalText t : r.texts()) byLang.put(t.language(), t);
        for (String lang : List.of("en", "ta")) {
            LegalText t = byLang.get(lang);
            if (t == null || blank(t.title()) || blank(t.body())) throw ApiException.badRequest("LEGAL_TEXT_REQUIRED", "Give the title and text in English and Tamil.");
        }
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from sf_legal_documents where kind = :k").param("k", r.kind()).query(Integer.class).single();
        for (String lang : List.of("en", "ta")) {
            LegalText t = byLang.get(lang);
            jdbc.sql("""
                            insert into sf_legal_documents (kind, version, language, title, body, changes, published_by)
                            values (:k, :v, :l, :t, :b, :c, :u)""")
                    .param("k", r.kind()).param("v", version).param("l", lang).param("t", t.title().trim()).param("b", t.body())
                    .param("c", blank(r.changes()) ? null : r.changes().trim()).param("u", admin.id()).update();
        }
        platformAudit.record(admin.id(), "PLATFORM_LEGAL_PUBLISHED", "LEGAL", (long) version, Map.of("kind", r.kind()));
        return allDocuments().stream().filter(d -> d.kind().equals(r.kind()) && d.version() == version).toList();
    }

    // ---- Consent --------------------------------------------------------------------------------------------------------

    public record Consent(String kind, int version, boolean accepted, String client, Instant recordedAt) {}

    /** Records acceptance of {@code KIND:version} entries, which must be published versions. */
    @Transactional
    public List<Consent> accept(CurrentUser user, List<String> documents, String client) {
        String c = List.of("WEB", "MOBILE").contains(client) ? client : "API";
        for (String d : documents == null ? List.<String>of() : documents) {
            String[] parts = d.split(":");
            if (parts.length != 2) throw ApiException.badRequest("UNKNOWN_DOCUMENT", "Unknown document.");
            int version;
            try { version = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { throw ApiException.badRequest("UNKNOWN_DOCUMENT", "Unknown document."); }
            boolean exists = jdbc.sql("select exists (select 1 from sf_legal_documents where kind = :k and version = :v)")
                    .param("k", parts[0]).param("v", version).query(Boolean.class).single();
            if (!exists) throw ApiException.badRequest("UNKNOWN_DOCUMENT", "Unknown document.");
            jdbc.sql("insert into sf_consents (user_id, kind, version, accepted, client) values (:u, :k, :v, true, :c)")
                    .param("u", user.id()).param("k", parts[0]).param("v", version).param("c", c).update();
        }
        return consents(user.id());
    }

    /** Marketing email is off unless the user turns it on (O12). */
    @Transactional
    public List<Consent> setMarketing(CurrentUser user, boolean optIn, String client) {
        jdbc.sql("update sf_users set marketing_opt_in = :m, updated_at = now() where id = :u").param("m", optIn).param("u", user.id()).update();
        jdbc.sql("insert into sf_consents (user_id, kind, version, accepted, client) values (:u, 'MARKETING', 1, :a, :c)")
                .param("u", user.id()).param("a", optIn).param("c", List.of("WEB", "MOBILE").contains(client) ? client : "API").update();
        return consents(user.id());
    }

    public List<Consent> consents(long userId) {
        return jdbc.sql("select kind, version, accepted, client, recorded_at from sf_consents where user_id = :u order by recorded_at desc, id desc")
                .param("u", userId).query((rs, i) -> new Consent(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4), rs.getTimestamp(5).toInstant())).list();
    }

    // ---- The user's own data --------------------------------------------------------------------------------------------

    public record MembershipView(long tenantId, String tenantName, List<String> roles, Instant since) {}

    public record PersonalData(long id, String email, String displayName, String preferredLanguage, Instant createdAt, Instant emailVerifiedAt,
                               boolean marketingOptIn, boolean twoStep, List<MembershipView> memberships, List<Consent> consents, Instant exportedAt) {}

    /** Everything the platform keeps about the person (not the farm businesses' records, which belong to those businesses). */
    @Transactional(readOnly = true)
    public PersonalData personalData(CurrentUser user) {
        Map<String, Object> u = jdbc.sql("""
                        select email, display_name, preferred_language, created_at, email_verified_at, marketing_opt_in,
                               exists (select 1 from sf_user_mfa f where f.user_id = sf_users.id and f.enabled_at is not null) as two_step
                        from sf_users where id = :u""").param("u", user.id()).query().singleRow();
        List<MembershipView> memberships = jdbc.sql("""
                        select t.id, t.name, coalesce(string_agg(r.code, ',' order by r.code), ''), m.created_at from sf_tenant_memberships m
                        join sf_tenants t on t.id = m.tenant_id left join sf_membership_roles mr on mr.membership_id = m.id left join sf_roles r on r.id = mr.role_id
                        where m.user_id = :u and m.active group by t.id, t.name, m.created_at order by t.name""")
                .param("u", user.id()).query((rs, i) -> new MembershipView(rs.getLong(1), rs.getString(2),
                        rs.getString(3).isEmpty() ? List.of() : List.of(rs.getString(3).split(",")), rs.getTimestamp(4).toInstant())).list();
        return new PersonalData(user.id(), (String) u.get("email"), (String) u.get("display_name"), (String) u.get("preferred_language"),
                ((Timestamp) u.get("created_at")).toInstant(), u.get("email_verified_at") == null ? null : ((Timestamp) u.get("email_verified_at")).toInstant(),
                (Boolean) u.get("marketing_opt_in"), (Boolean) u.get("two_step"), memberships, consents(user.id()), clock.instant());
    }

    // ---- Deleting the account (O11) -------------------------------------------------------------------------------------

    /**
     * Deletes the account: its name, email and sign-in are replaced with a former-member label, its sessions, two-step
     * secrets and memberships end. Records the person made stay in the farm businesses' books. Refused while the person is
     * the only owner of an active farm business.
     */
    @Transactional
    public void deleteAccount(CurrentUser user, String password) {
        Map<String, Object> u = jdbc.sql("select email, password_hash, preferred_language, platform_role from sf_users where id = :u and active")
                .param("u", user.id()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Sign in is required."));
        if (password == null || !passwords.matches(password, (String) u.get("password_hash"))) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "The current password is incorrect.");
        }
        if ("PLATFORM_ADMIN".equals(u.get("platform_role"))) {
            throw ApiException.conflict("PLATFORM_ADMIN_ACCOUNT", "A platform administrator's account is removed by another administrator.");
        }
        List<String> soleOwner = jdbc.sql("""
                        select t.name from sf_tenant_memberships m join sf_tenants t on t.id = m.tenant_id
                        join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                        where m.user_id = :u and m.active and r.code = 'TENANT_OWNER' and t.deletion_requested_at is null
                          and not exists (select 1 from sf_tenant_memberships o join sf_membership_roles omr on omr.membership_id = o.id
                                          join sf_roles orr on orr.id = omr.role_id
                                          where o.tenant_id = m.tenant_id and o.active and o.user_id <> :u and orr.code = 'TENANT_OWNER')
                        order by t.name""").param("u", user.id()).query(String.class).list();
        if (!soleOwner.isEmpty()) {
            throw ApiException.conflict("SOLE_OWNER", "You are the only owner of " + String.join(", ", soleOwner)
                    + ". Make someone else an owner, or ask for the farm business to be deleted first.");
        }
        String address = (String) u.get("email");
        String language = (String) u.get("preferred_language");
        Instant now = clock.instant();
        // A unique, unusable address and password; the label is what the farm businesses' records show from now on
        jdbc.sql("""
                        update sf_users set email = :e, display_name = :n, password_hash = :p, active = false, platform_role = null,
                          anonymised_at = :now, marketing_opt_in = false, updated_at = now() where id = :u""")
                .param("e", "deleted-" + user.id() + "-" + UUID.randomUUID() + "@deleted.invalid").param("n", "Former member #" + user.id())
                .param("p", "!deleted").param("now", Timestamp.from(now)).param("u", user.id()).update();
        jdbc.sql("update sf_refresh_tokens set revoked_at = now(), revoke_reason = :r where user_id = :u and revoked_at is null")
                .param("r", SfRefreshToken.LOGOUT).param("u", user.id()).update();
        jdbc.sql("delete from sf_user_recovery_codes where user_id = :u").param("u", user.id()).update();
        jdbc.sql("delete from sf_user_mfa where user_id = :u").param("u", user.id()).update();
        jdbc.sql("update sf_password_reset_tokens set used_at = now() where user_id = :u and used_at is null").param("u", user.id()).update();
        jdbc.sql("update sf_email_verification_tokens set used_at = now() where user_id = :u and used_at is null").param("u", user.id()).update();
        List<Long> tenants = jdbc.sql("select tenant_id from sf_tenant_memberships where user_id = :u and active").param("u", user.id()).query(Long.class).list();
        jdbc.sql("update sf_tenant_memberships set active = false where user_id = :u").param("u", user.id()).update();
        for (long t : tenants) audit.record(t, user.id(), "MEMBER_ACCOUNT_DELETED", "USER", user.id(), Map.of());
        platformAudit.record(user.id(), "ACCOUNT_DELETED", "USER", user.id(), Map.of());
        AfterCommit.run(() -> email.sendNotice(address, language, EmailSender.Notice.ACCOUNT_DELETED, Map.of()));
    }

    private static LegalDocument doc(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LegalDocument(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant());
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
