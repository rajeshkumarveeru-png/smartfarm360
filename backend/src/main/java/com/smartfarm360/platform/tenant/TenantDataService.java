package com.smartfarm360.platform.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.export.CsvWriter;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.plan.PlanLimits;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Owners' data (SaaS operations design, section 8): a whole farm business exported as a ZIP of CSV and JSON files, and
 * its deletion after 30 days' grace (O9, O10). Both work from the catalog of tables with a {@code tenant_id}, so a new
 * module's tables are exported and deleted without changes here.
 */
@Service
public class TenantDataService {
    private static final Logger log = LoggerFactory.getLogger(TenantDataService.class);
    public static final Duration GRACE = Duration.ofDays(30);
    public static final Duration EXPORT_KEPT = Duration.ofDays(7);
    /** Tables that are not the business's records: the sync replay store and the exports themselves. */
    private static final Set<String> NOT_EXPORTED = Set.of("sf_idempotent_requests", "sf_tenant_exports");
    /** Columns that hold secrets, never exported. */
    private static final Set<String> SECRET_COLUMNS = Set.of("token_hash", "password_hash", "secret_enc", "code_hash");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final PlanLimits limits;
    private final EmailSender email;
    private final ObjectMapper json;
    private final Clock clock;
    private final Path dir;
    private final ThreadPoolTaskExecutor workers;

    public TenantDataService(JdbcClient jdbc, JdbcTemplate template, javax.sql.DataSource dataSource, AuditService audit, PlatformAudit platformAudit,
                             PlanLimits limits, EmailSender email, ObjectMapper json, Clock clock, @Value("${app.export.dir}") String dir) {
        this.jdbc = jdbc;
        this.template = template;
        // A JDBC transaction manager, because the deletion orders itself with savepoints (the JPA one has none)
        this.tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.limits = limits;
        this.email = email;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock;
        this.dir = Path.of(dir);
        // At most two exports run at a time, so a large export cannot slow everyone else down (design section 14)
        this.workers = new ThreadPoolTaskExecutor();
        this.workers.setCorePoolSize(2);
        this.workers.setMaxPoolSize(2);
        this.workers.setThreadNamePrefix("sf-export-");
        this.workers.initialize();
    }

    // ---- Export (O9) -----------------------------------------------------------------------------------------------------

    public record Export(long id, String status, Long sizeBytes, Instant requestedAt, Instant readyAt, Instant expiresAt, String requestedByEmail) {}

    @Transactional
    public Export requestExport(TenantContext ctx) {
        requireOwner(ctx);
        limits.requireExportSlot(ctx.tenantId());
        long id = jdbc.sql("insert into sf_tenant_exports (tenant_id, requested_by, status) values (:t, :u, 'QUEUED') returning id")
                .param("t", ctx.tenantId()).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "TENANT_EXPORT_REQUESTED", "TENANT_EXPORT", id, Map.of());
        AfterCommit.run(() -> workers.execute(() -> runExport(id)));
        return export(ctx.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public List<Export> exports(TenantContext ctx) {
        requireOwner(ctx);
        return jdbc.sql(EXPORT_COLUMNS + " where e.tenant_id = :t order by e.requested_at desc limit 10").param("t", ctx.tenantId()).query((rs, i) -> exportRow(rs)).list();
    }

    private static final String EXPORT_COLUMNS = """
            select e.id, e.status, e.size_bytes, e.requested_at, e.ready_at, e.expires_at, u.email from sf_tenant_exports e join sf_users u on u.id = e.requested_by""";

    private static Export exportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Export(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), ts(rs.getTimestamp(4)), ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)), rs.getString(7));
    }

    private Export export(long tenantId, long id) {
        return jdbc.sql(EXPORT_COLUMNS + " where e.tenant_id = :t and e.id = :id").param("t", tenantId).param("id", id).query((rs, i) -> exportRow(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("EXPORT_NOT_FOUND", "Export not found."));
    }

    public record Download(Resource file, String fileName) {}

    @Transactional
    public Download download(TenantContext ctx, long id) {
        requireOwner(ctx);
        Map<String, Object> row = jdbc.sql("select status, file_name, expires_at from sf_tenant_exports where tenant_id = :t and id = :id")
                .param("t", ctx.tenantId()).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("EXPORT_NOT_FOUND", "Export not found."));
        Timestamp expires = (Timestamp) row.get("expires_at");
        Path file = row.get("file_name") == null ? null : dir.resolve((String) row.get("file_name"));
        if (!"READY".equals(row.get("status")) || expires == null || expires.toInstant().isBefore(clock.instant()) || file == null || !Files.exists(file)) {
            throw ApiException.conflict("EXPORT_NOT_READY", "This export is not ready or has expired.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "TENANT_EXPORT_DOWNLOADED", "TENANT_EXPORT", id, Map.of());
        String name = "smartfarm360-" + slug(ctx.tenantId()) + "-" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC) + ".zip";
        return new Download(new FileSystemResource(file), name);
    }

    /** Builds the ZIP (in the background; runs in the "all" scope, filtering by tenant itself). */
    void runExport(long id) {
        Map<String, Object> row = jdbc.sql("select tenant_id, requested_by from sf_tenant_exports where id = :id").param("id", id).query().singleRow();
        long tenantId = ((Number) row.get("tenant_id")).longValue();
        long requestedBy = ((Number) row.get("requested_by")).longValue();
        jdbc.sql("update sf_tenant_exports set status = 'RUNNING' where id = :id").param("id", id).update();
        String fileName = "tenant-" + tenantId + "-export-" + id + "-" + Long.toHexString(RANDOM.nextLong()) + ".zip";
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(file)) {
                writeExport(tenantId, out);
            }
            Instant now = clock.instant();
            jdbc.sql("update sf_tenant_exports set status = 'READY', file_name = :f, size_bytes = :s, ready_at = :r, expires_at = :e where id = :id")
                    .param("f", fileName).param("s", Files.size(file)).param("r", Timestamp.from(now)).param("e", Timestamp.from(now.plus(EXPORT_KEPT)))
                    .param("id", id).update();
            platformAudit.record(requestedBy, "TENANT_EXPORT_READY", "TENANT", tenantId, Map.of("export", id));
            Map<String, Object> to = jdbc.sql("select u.email, u.preferred_language, t.name from sf_users u, sf_tenants t where u.id = :u and t.id = :t")
                    .param("u", requestedBy).param("t", tenantId).query().singleRow();
            email.sendNotice((String) to.get("email"), (String) to.get("preferred_language"), EmailSender.Notice.EXPORT_READY, Map.of("tenant", (String) to.get("name")));
        } catch (IOException | RuntimeException e) {
            log.error("Export {} of tenant {} failed", id, tenantId, e);
            jdbc.sql("update sf_tenant_exports set status = 'FAILED', error = :e where id = :id")
                    .param("e", e.getClass().getSimpleName()).param("id", id).update();
        }
    }

    /** Every table of the business, as {@code <table>.csv} and {@code <table>.json}, with the business and its members, and a README. */
    void writeExport(long tenantId, OutputStream out) throws IOException {
        StringBuilder readme = new StringBuilder();
        Map<String, Object> tenant = jdbc.sql("select id, name, slug, preferred_language, plan_code, created_at from sf_tenants where id = :t")
                .param("t", tenantId).query().singleRow();
        readme.append("SmartFarm360 export of ").append(tenant.get("name")).append("\n")
                .append("Made ").append(clock.instant()).append(" (UTC). The farm business was opened ").append(tenant.get("created_at")).append(".\n\n")
                .append("Each table is given twice: a CSV file (for spreadsheets) and a JSON file (for other programs).\n")
                .append("Amounts are exact decimals. Dates are farm dates; times are UTC.\n")
                .append("Ids link the files together: for example document_id in one file is id in sf_finance_documents.\n\n");
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            write(zip, readme, "farm_business", List.of(tenant));
            write(zip, readme, "members", template.queryForList("""
                    select m.id as membership_id, u.id as user_id, u.email, u.display_name, m.active, m.created_at,
                           (select string_agg(r.code, ',' order by r.code) from sf_membership_roles mr join sf_roles r on r.id = mr.role_id where mr.membership_id = m.id) as roles
                    from sf_tenant_memberships m join sf_users u on u.id = m.user_id where m.tenant_id = ? order by m.id""", tenantId));
            for (String table : tenantTables()) {
                if (NOT_EXPORTED.contains(table)) continue;
                write(zip, readme, table, template.queryForList("select * from " + table + " where tenant_id = ? order by 1", tenantId));
            }
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(readme.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void write(ZipOutputStream zip, StringBuilder readme, String name, List<Map<String, Object>> rows) throws IOException {
        List<String> columns = rows.isEmpty() ? columns(name) : new ArrayList<>(rows.get(0).keySet());
        columns.removeIf(SECRET_COLUMNS::contains);
        List<Map<String, Object>> clean = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String c : columns) m.put(c, value(r.get(c)));
            return m;
        }).toList();
        StringBuilder csv = new StringBuilder(CsvWriter.row(columns.toArray(String[]::new)));
        for (Map<String, Object> r : clean) csv.append(CsvWriter.row(columns.stream().map(c -> r.get(c) == null ? "" : String.valueOf(r.get(c))).toArray(String[]::new)));
        zip.putNextEntry(new ZipEntry(name + ".csv"));
        zip.write(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry(name + ".json"));
        zip.write(json.writeValueAsBytes(clean));
        zip.closeEntry();
        readme.append(name).append(" (").append(rows.size()).append(" rows): ").append(String.join(", ", columns)).append("\n");
    }

    private List<String> columns(String table) {
        return new ArrayList<>(jdbc.sql("select column_name from information_schema.columns where table_schema = current_schema() and table_name = :t order by ordinal_position")
                .param("t", table).query(String.class).list());
    }

    private static Object value(Object v) {
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.math.BigDecimal b) return b.toPlainString();
        if (v != null && "org.postgresql.util.PGobject".equals(v.getClass().getName())) return v.toString();
        if (v instanceof java.util.UUID u) return u.toString();
        return v;
    }

    /** Every table with a {@code tenant_id}, the same list Row-Level Security covers. */
    List<String> tenantTables() {
        return jdbc.sql("""
                        select c.table_name from information_schema.columns c join information_schema.tables t
                          on t.table_schema = c.table_schema and t.table_name = c.table_name
                        where c.table_schema = current_schema() and c.column_name = 'tenant_id' and t.table_type = 'BASE TABLE' and c.table_name like 'sf\\_%'
                        order by c.table_name""").query(String.class).list();
    }

    /** Exports older than 7 days are removed (daily). */
    @Scheduled(cron = "${app.jobs.deletion-cron}")
    public void expireExports() {
        List<Map<String, Object>> due = jdbc.sql("select id, file_name from sf_tenant_exports where status = 'READY' and expires_at < now()").query().listOfRows();
        for (Map<String, Object> e : due) {
            try {
                if (e.get("file_name") != null) Files.deleteIfExists(dir.resolve((String) e.get("file_name")));
            } catch (IOException ex) {
                log.warn("Could not remove expired export {}", e.get("id"));
            }
            jdbc.sql("update sf_tenant_exports set status = 'EXPIRED' where id = :id").param("id", e.get("id")).update();
        }
    }

    // ---- Deletion (O10) --------------------------------------------------------------------------------------------------

    public record DeletionState(boolean pending, Instant requestedAt, Instant scheduledFor, String requestedByEmail) {}

    @Transactional(readOnly = true)
    public DeletionState deletionState(TenantContext ctx) {
        return jdbc.sql("""
                        select t.deletion_requested_at, t.deletion_scheduled_for, u.email from sf_tenants t left join sf_users u on u.id = t.deletion_requested_by
                        where t.id = :t""").param("t", ctx.tenantId())
                .query((rs, i) -> new DeletionState(rs.getTimestamp(1) != null, ts(rs.getTimestamp(1)), ts(rs.getTimestamp(2)), rs.getString(3))).single();
    }

    /** An owner asks for deletion, typing the business's name. It happens after 30 days; until then any owner can cancel. */
    @Transactional
    public DeletionState requestDeletion(TenantContext ctx, String confirmName) {
        requireOwner(ctx);
        String name = jdbc.sql("select name from sf_tenants where id = :t").param("t", ctx.tenantId()).query(String.class).single();
        if (confirmName == null || !confirmName.trim().equalsIgnoreCase(name.trim())) {
            throw ApiException.badRequest("CONFIRM_NAME_MISMATCH", "Type the farm business's name exactly to confirm.");
        }
        Instant now = clock.instant();
        int n = jdbc.sql("""
                        update sf_tenants set deletion_requested_at = :now, deletion_requested_by = :u, deletion_scheduled_for = :when, updated_at = now()
                        where id = :t and deletion_requested_at is null""")
                .param("now", Timestamp.from(now)).param("u", ctx.userId()).param("when", Timestamp.from(now.plus(GRACE))).param("t", ctx.tenantId()).update();
        if (n == 0) throw ApiException.conflict("DELETION_ALREADY_REQUESTED", "Deletion was already asked for.");
        audit.record(ctx.tenantId(), ctx.userId(), "TENANT_DELETION_REQUESTED", "TENANT", ctx.tenantId(), Map.of());
        platformAudit.record(ctx.userId(), "TENANT_DELETION_REQUESTED", "TENANT", ctx.tenantId(), Map.of("name", name));
        notifyOwners(ctx.tenantId(), EmailSender.Notice.DELETION_SCHEDULED, Map.of("tenant", name, "date", LocalDate.ofInstant(now.plus(GRACE), ZoneOffset.UTC).toString()));
        return deletionState(ctx);
    }

    @Transactional
    public DeletionState cancelDeletion(TenantContext ctx) {
        requireOwner(ctx);
        int n = jdbc.sql("""
                        update sf_tenants set deletion_requested_at = null, deletion_requested_by = null, deletion_scheduled_for = null, updated_at = now()
                        where id = :t and deletion_requested_at is not null""").param("t", ctx.tenantId()).update();
        if (n == 0) throw ApiException.conflict("DELETION_NOT_REQUESTED", "No deletion was asked for.");
        String name = jdbc.sql("select name from sf_tenants where id = :t").param("t", ctx.tenantId()).query(String.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "TENANT_DELETION_CANCELLED", "TENANT", ctx.tenantId(), Map.of());
        platformAudit.record(ctx.userId(), "TENANT_DELETION_CANCELLED", "TENANT", ctx.tenantId(), Map.of("name", name));
        notifyOwners(ctx.tenantId(), EmailSender.Notice.DELETION_CANCELLED, Map.of("tenant", name));
        return deletionState(ctx);
    }

    /** The daily job: deletes every business whose 30 days are over, one at a time. Returns how many were deleted. */
    @Scheduled(cron = "${app.jobs.deletion-cron}")
    public void scheduledDeletions() { runDueDeletions(clock.instant()); }

    public int runDueDeletions(Instant now) {
        List<Long> due = jdbc.sql("select id from sf_tenants where deletion_scheduled_for is not null and deletion_scheduled_for <= :now order by deletion_scheduled_for")
                .param("now", Timestamp.from(now)).query(Long.class).list();
        int done = 0;
        for (long id : due) {
            try {
                deleteTenant(id);
                done++;
            } catch (RuntimeException e) {
                log.error("Deleting tenant {} failed; it stays scheduled", id, e);
            }
        }
        return done;
    }

    /** Removes every row of the business in one transaction, then its export files. Keeps only a deletion record. */
    void deleteTenant(long tenantId) {
        Map<String, Object> t = jdbc.sql("select name, deletion_requested_by from sf_tenants where id = :t").param("t", tenantId).query().singleRow();
        String name = (String) t.get("name");
        Long askedBy = t.get("deletion_requested_by") == null ? null : ((Number) t.get("deletion_requested_by")).longValue();
        List<Map<String, Object>> owners = owners(tenantId);
        List<String> files = jdbc.sql("select file_name from sf_tenant_exports where tenant_id = :t and file_name is not null").param("t", tenantId).query(String.class).list();
        Map<String, Integer> counts = new TreeMap<>();
        tx.executeWithoutResult(status -> {
            // Lets the append-only guards allow this deletion, in this transaction only (changeset 057)
            template.queryForList("select set_config('app.tenant_deletion', 'on', true)");
            // Link tables without a tenant_id first (role links of memberships, invitations and the business's own roles)
            List<Map<String, Object>> links = template.queryForList("""
                    select c.conrelid::regclass::text as child, a.attname as child_column, c.confrelid::regclass::text as parent, af.attname as parent_column
                    from pg_constraint c
                    join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                    join pg_attribute af on af.attrelid = c.confrelid and af.attnum = c.confkey[1]
                    where c.contype = 'f' and array_length(c.conkey, 1) = 1
                      and not exists (select 1 from information_schema.columns x where x.table_schema = current_schema() and x.table_name = c.conrelid::regclass::text and x.column_name = 'tenant_id')
                      and exists (select 1 from information_schema.columns y where y.table_schema = current_schema() and y.table_name = c.confrelid::regclass::text and y.column_name = 'tenant_id')""");
            for (Map<String, Object> l : links) {
                template.update("delete from " + l.get("child") + " where " + l.get("child_column") + " in (select " + l.get("parent_column") + " from "
                        + l.get("parent") + " where tenant_id = ?)", tenantId);
            }
            // Then every tenant table; rows referenced by another table's rows wait for a later pass
            List<String> remaining = new ArrayList<>(tenantTables());
            for (int pass = 0; !remaining.isEmpty(); pass++) {
                if (pass > remaining.size() + 5) throw new IllegalStateException("Could not order the deletion of " + remaining);
                List<String> next = new ArrayList<>();
                for (String table : remaining) {
                    Object savepoint = status.createSavepoint();
                    try {
                        int n = template.update("delete from " + table + " where tenant_id = ?", tenantId);
                        counts.merge(table, n, Integer::sum);
                        status.releaseSavepoint(savepoint);
                    } catch (DataIntegrityViolationException e) {
                        status.rollbackToSavepoint(savepoint);
                        next.add(table);
                    }
                }
                if (next.size() == remaining.size() && pass > 0) throw new IllegalStateException("Could not order the deletion of " + next);
                remaining = next;
            }
            template.update("delete from sf_tenants where id = ?", tenantId);
            platformAudit.record(askedBy, "TENANT_DELETED", "TENANT", tenantId, Map.of("name", name));
        });
        for (String f : files) {
            try { Files.deleteIfExists(dir.resolve(f)); } catch (IOException e) { log.warn("Could not remove an export file of deleted tenant {}", tenantId); }
        }
        for (Map<String, Object> o : owners) {
            email.sendNotice((String) o.get("email"), (String) o.get("preferred_language"), EmailSender.Notice.DELETION_DONE, Map.of("tenant", name));
        }
        log.info("Tenant {} deleted ({} tables)", tenantId, counts.size());
    }

    private List<Map<String, Object>> owners(long tenantId) {
        return jdbc.sql("""
                        select distinct u.email, u.preferred_language from sf_tenant_memberships m join sf_users u on u.id = m.user_id
                        join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                        where m.tenant_id = :t and m.active and r.code = 'TENANT_OWNER' and u.active""").param("t", tenantId).query().listOfRows();
    }

    private void notifyOwners(long tenantId, EmailSender.Notice notice, Map<String, String> params) {
        List<Map<String, Object>> owners = owners(tenantId);
        AfterCommit.run(() -> owners.forEach(o -> email.sendNotice((String) o.get("email"), (String) o.get("preferred_language"), notice, params)));
    }

    private String slug(long tenantId) {
        return jdbc.sql("select slug from sf_tenants where id = :t").param("t", tenantId).query(String.class).single();
    }

    private static void requireOwner(TenantContext ctx) {
        if (!ctx.hasRole("TENANT_OWNER")) throw ApiException.forbidden("OWNER_REQUIRED", "Only an owner can do this.");
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
}
