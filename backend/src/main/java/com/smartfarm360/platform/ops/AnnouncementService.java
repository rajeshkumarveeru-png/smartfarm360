package com.smartfarm360.platform.ops;

import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Banners in the web and phone apps, in English and Tamil, and read-only maintenance (SaaS operations design,
 * section 7, O8). While a maintenance announcement is running, {@link MaintenanceFilter} refuses every change.
 */
@Service
public class AnnouncementService {
    /** Maintenance state is read at most every 5 seconds, not on every request. */
    static final long CACHE_MILLIS = 5_000;
    private final JdbcClient jdbc;
    private final PlatformAudit platformAudit;
    private final Clock clock;
    private volatile long checkedAt = -1;
    private volatile boolean maintenance;

    public AnnouncementService(JdbcClient jdbc, PlatformAudit platformAudit, Clock clock) {
        this.jdbc = jdbc;
        this.platformAudit = platformAudit;
        this.clock = clock;
    }

    public record Announcement(long id, String messageEn, String messageTa, Instant startsAt, Instant endsAt, boolean maintenance, Instant endedAt) {}

    public record AnnouncementRequest(String messageEn, String messageTa, Instant startsAt, Instant endsAt, boolean maintenance) {}

    private static final String COLUMNS = "select id, message_en, message_ta, starts_at, ends_at, maintenance, ended_at from sf_announcements";

    private static Announcement row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp ended = rs.getTimestamp(7);
        return new Announcement(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                rs.getBoolean(6), ended == null ? null : ended.toInstant());
    }

    /** What every app shows now. */
    @Transactional(readOnly = true)
    public List<Announcement> active() {
        return jdbc.sql(COLUMNS + " where ended_at is null and starts_at <= :now and ends_at > :now order by maintenance desc, starts_at")
                .param("now", Timestamp.from(clock.instant())).query((rs, i) -> row(rs)).list();
    }

    @Transactional(readOnly = true)
    public List<Announcement> all() {
        return jdbc.sql(COLUMNS + " order by created_at desc limit 50").query((rs, i) -> row(rs)).list();
    }

    @Transactional
    public Announcement create(CurrentUser admin, AnnouncementRequest r) {
        if (blank(r.messageEn()) || blank(r.messageTa())) throw ApiException.badRequest("MESSAGE_REQUIRED", "Write the message in English and Tamil.");
        Instant starts = r.startsAt() == null ? clock.instant() : r.startsAt();
        if (r.endsAt() == null || !r.endsAt().isAfter(starts)) throw ApiException.badRequest("INVALID_PERIOD", "The end must be after the start.");
        long id = jdbc.sql("""
                        insert into sf_announcements (message_en, message_ta, starts_at, ends_at, maintenance, created_by)
                        values (:en, :ta, :s, :e, :m, :u) returning id""")
                .param("en", r.messageEn().trim()).param("ta", r.messageTa().trim()).param("s", Timestamp.from(starts)).param("e", Timestamp.from(r.endsAt()))
                .param("m", r.maintenance()).param("u", admin.id()).query(Long.class).single();
        platformAudit.record(admin.id(), r.maintenance() ? "PLATFORM_MAINTENANCE_SCHEDULED" : "PLATFORM_ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", id, Map.of());
        checkedAt = -1;
        return jdbc.sql(COLUMNS + " where id = :id").param("id", id).query((rs, i) -> row(rs)).single();
    }

    @Transactional
    public void end(CurrentUser admin, long id) {
        int n = jdbc.sql("update sf_announcements set ended_at = now() where id = :id and ended_at is null").param("id", id).update();
        if (n == 0) throw ApiException.notFound("ANNOUNCEMENT_NOT_FOUND", "Announcement not found or already ended.");
        platformAudit.record(admin.id(), "PLATFORM_ANNOUNCEMENT_ENDED", "ANNOUNCEMENT", id, Map.of());
        checkedAt = -1;
    }

    /** True while a maintenance announcement is running. */
    public boolean maintenanceActive() {
        long now = clock.millis();
        if (checkedAt < 0 || now - checkedAt > CACHE_MILLIS) {
            maintenance = jdbc.sql("select exists (select 1 from sf_announcements where maintenance and ended_at is null and starts_at <= :now and ends_at > :now)")
                    .param("now", Timestamp.from(clock.instant())).query(Boolean.class).single();
            checkedAt = now;
        }
        return maintenance;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
