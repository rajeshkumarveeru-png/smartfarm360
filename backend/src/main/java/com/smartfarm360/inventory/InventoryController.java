package com.smartfarm360.inventory;

import com.smartfarm360.inventory.InventoryDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) { this.service = service; }

    @GetMapping("/suppliers")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Map<String, Object>> suppliers(TenantContext ctx) { return service.suppliers(ctx); }

    @PostMapping("/suppliers")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSupplier(TenantContext ctx, @Valid @RequestBody SupplierRequest r) { return service.createSupplier(ctx, r); }

    @GetMapping("/inventory/items")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Map<String, Object>> items(TenantContext ctx) { return service.items(ctx); }

    @PostMapping("/inventory/items")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createItem(TenantContext ctx, @Valid @RequestBody ItemRequest r) { return service.createItem(ctx, r); }

    @GetMapping("/purchases")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Map<String, Object>> purchases(TenantContext ctx) { return service.purchases(ctx); }

    @PostMapping("/purchases")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPurchase(TenantContext ctx, CurrentUser user, @Valid @RequestBody PurchaseRequest r) {
        return service.createPurchase(ctx, user, r);
    }
}
