package com.smartfarm360.platform.bin;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * A Bin kind backed by one table with {@code deleted_at} and {@code deleted_by}. Subclasses name the table, how the item
 * is called ({@code nameSql} and {@code detailSql} over alias {@code x} and their {@code joins}), and what delete forever
 * erases; the hooks add their own rules (keep a cost object in step, refuse while a parent is in the Bin).
 */
public abstract class TableBinKind implements BinKind {
    protected final JdbcClient jdbc;
    private final String code;
    private final Permission permission;
    private final String table;
    private final String nameSql;
    private final String detailSql;
    private final String joins;
    private final String notFoundCode;

    protected TableBinKind(JdbcClient jdbc, String code, Permission permission, String table, String nameSql, String detailSql, String joins, String notFoundCode) {
        this.jdbc = jdbc;
        this.code = code;
        this.permission = permission;
        this.table = table;
        this.nameSql = nameSql;
        this.detailSql = detailSql;
        this.joins = joins;
        this.notFoundCode = notFoundCode;
    }

    @Override public String code() { return code; }
    @Override public Permission permission() { return permission; }

    /** Name and Bin state of a row of the tenant; not found when the id is missing or another tenant's. */
    private record Row(String name, boolean inBin) {}

    private Row row(TenantContext ctx, long id) {
        return jdbc.sql("select " + nameSql + ", x.deleted_at is not null from " + table + " x " + joins + " where x.id = :id and x.tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> new Row(rs.getString(1), rs.getBoolean(2))).optional()
                .orElseThrow(() -> ApiException.notFound(notFoundCode, "Item not found."));
    }

    /** Refuses the delete when the item cannot go to the Bin (it moved money, or has animals). */
    protected void beforeDelete(TenantContext ctx, long id) {}
    /** Extra changes when the item goes to the Bin (its cost object stops being offered). */
    protected void afterDelete(TenantContext ctx, long id) {}
    /** Refuses the restore while what the item belongs to is still in the Bin. */
    protected void beforeRestore(TenantContext ctx, long id) {}
    protected void afterRestore(TenantContext ctx, long id) {}
    /** Erases the item and what belongs only to it; the database refuses while money or stock records refer to it. */
    protected abstract void erase(TenantContext ctx, long id);
    /** Extra columns set when deleting, for tables that also have an {@code active} flag. */
    protected String deleteSet() { return ""; }
    protected String restoreSet() { return ""; }

    @Override
    @Transactional
    public String delete(TenantContext ctx, long id) {
        Row r = row(ctx, id);
        if (r.inBin()) throw ApiException.conflict("BIN_ALREADY", "This item is already in the Bin.");
        beforeDelete(ctx, id);
        jdbc.sql("update " + table + " set deleted_at = :now, deleted_by = :u" + deleteSet() + " where id = :id and tenant_id = :t")
                .param("now", java.sql.Timestamp.from(Instant.now())).param("u", ctx.userId()).param("id", id).param("t", ctx.tenantId()).update();
        afterDelete(ctx, id);
        return r.name();
    }

    @Override
    @Transactional
    public String restore(TenantContext ctx, long id) {
        Row r = row(ctx, id);
        if (!r.inBin()) throw ApiException.conflict("BIN_NOT_IN_BIN", "This item is not in the Bin.");
        beforeRestore(ctx, id);
        jdbc.sql("update " + table + " set deleted_at = null, deleted_by = null" + restoreSet() + " where id = :id and tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).update();
        afterRestore(ctx, id);
        return r.name();
    }

    @Override
    public String purge(TenantContext ctx, long id) {
        Row r = row(ctx, id);
        if (!r.inBin()) throw ApiException.conflict("BIN_NOT_IN_BIN", "Move the item to the Bin before deleting it forever.");
        erase(ctx, id);
        return r.name();
    }

    @Override
    public List<BinItem> deleted(TenantContext ctx) {
        return jdbc.sql("select x.id, " + nameSql + ", " + detailSql + ", x.deleted_at, u.display_name from " + table + " x " + joins
                        + " left join sf_users u on u.id = x.deleted_by where x.tenant_id = :t and x.deleted_at is not null")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new BinItem(code, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5))).list();
    }

    /** The parent is in the Bin: restore it first. */
    protected void requireParentKept(boolean parentInBin, String parentName) {
        if (parentInBin) throw ApiException.conflict("BIN_PARENT_DELETED", "Restore " + parentName + " first: it is in the Bin.");
    }
}
