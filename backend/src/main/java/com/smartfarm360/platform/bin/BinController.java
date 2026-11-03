package com.smartfarm360.platform.bin;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The Bin of a farm business. Moving to the Bin and restoring check the permission of the item's kind in the service;
 * delete forever needs DATA_PURGE.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/bin")
public class BinController {
    private final BinService bin;

    public BinController(BinService bin) { this.bin = bin; }

    @GetMapping
    @RequiresPermission(Permission.TENANT_READ)
    public List<BinKind.BinItem> items(TenantContext ctx) { return bin.items(ctx); }

    @PostMapping("/{kind}/{id}")
    @RequiresPermission(Permission.TENANT_READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(TenantContext ctx, @PathVariable String kind, @PathVariable long id) { bin.delete(ctx, kind, id); }

    @PostMapping("/{kind}/{id}/restore")
    @RequiresPermission(Permission.TENANT_READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(TenantContext ctx, @PathVariable String kind, @PathVariable long id) { bin.restore(ctx, kind, id); }

    @DeleteMapping("/{kind}/{id}")
    @RequiresPermission(Permission.DATA_PURGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(TenantContext ctx, @PathVariable String kind, @PathVariable long id) { bin.purge(ctx, kind, id); }
}
