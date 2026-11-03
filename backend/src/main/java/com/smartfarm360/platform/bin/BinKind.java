package com.smartfarm360.platform.bin;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;

import java.util.List;

/**
 * One kind of item that can go to the Bin (bin and batch varieties design, B1-B9). Each module registers its own kinds,
 * so the Bin in platform never depends on farming code. Implementations check the tenant themselves: every query is
 * scoped by {@code ctx.tenantId()}, and a missing or foreign id is reported as not found.
 */
public interface BinKind {
    /** Stable code of the kind, used in URLs and the activity history: FARM, LAND_PARCEL, CROP_CYCLE… */
    String code();

    /** Who may move this kind to the Bin and restore it: the permission that already changes it. */
    Permission permission();

    /** Moves the item to the Bin: it leaves every list and choice; nothing is erased. Returns the item's name. */
    String delete(TenantContext ctx, long id);

    /** Brings the item back as it was. Returns the item's name. */
    String restore(TenantContext ctx, long id);

    /**
     * Erases the item and what belongs only to it. Runs in its own transaction: when money or stock records still refer
     * to the item, the database refuses and nothing is erased (B3). Returns the item's name.
     */
    String purge(TenantContext ctx, long id);

    /** The tenant's items of this kind in the Bin. */
    List<BinItem> deleted(TenantContext ctx);

    /** An item in the Bin: {@code detail} says where it belonged (the farm of a field, the crop of a cycle). */
    record BinItem(String kind, long id, String name, String detail, java.time.Instant deletedAt, String deletedBy) {}
}
