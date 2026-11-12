package com.smartfarm360.platform.bin;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Bin (bin and batch varieties design): delete moves an item there, restore brings it back, and delete forever
 * erases it. Every step is checked against the permission of the item's kind, and recorded in the activity history (B8).
 */
@Service
public class BinService {
    private final Map<String, BinKind> kinds;
    private final AuditService audit;
    private final TransactionTemplate own;

    public BinService(List<BinKind> kinds, AuditService audit, PlatformTransactionManager transactions) {
        this.kinds = kinds.stream().collect(Collectors.toMap(BinKind::code, Function.identity()));
        this.audit = audit;
        // Delete forever runs alone, so a refusal by the database rolls back the whole erase
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private BinKind kind(TenantContext ctx, String code) {
        BinKind k = kinds.get(code);
        if (k == null) throw ApiException.notFound("BIN_KIND_NOT_FOUND", "There is no such kind of item.");
        if (!ctx.has(k.permission())) throw ApiException.forbidden("ACCESS_DENIED", "You do not have permission to change this item.");
        return k;
    }

    /** What is in the Bin, newest first: only the kinds the member may change. */
    public List<BinKind.BinItem> items(TenantContext ctx) {
        return kinds.values().stream().filter(k -> ctx.has(k.permission())).flatMap(k -> k.deleted(ctx).stream())
                .sorted(Comparator.comparing(BinKind.BinItem::deletedAt).reversed()).toList();
    }

    public void delete(TenantContext ctx, String code, long id) {
        String name = kind(ctx, code).delete(ctx, id);
        audit.record(ctx.tenantId(), ctx.userId(), "BIN_DELETED", code, id, Map.of("name", name));
    }

    public void restore(TenantContext ctx, String code, long id) {
        String name = kind(ctx, code).restore(ctx, id);
        audit.record(ctx.tenantId(), ctx.userId(), "BIN_RESTORED", code, id, Map.of("name", name));
    }

    /** Delete forever (B2, B3, B5): the owner only, and refused while money or stock records refer to the item. */
    public void purge(TenantContext ctx, String code, long id) {
        if (!ctx.has(Permission.DATA_PURGE)) throw ApiException.forbidden("ACCESS_DENIED", "Only the owner can delete items forever.");
        BinKind k = kind(ctx, code);
        String name;
        try {
            name = own.execute(s -> k.purge(ctx, id));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("BIN_KEPT", "Money or stock records still refer to this item, so it is kept in the Bin.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "BIN_PURGED", code, id, Map.of("name", name == null ? "" : name));
    }
}
