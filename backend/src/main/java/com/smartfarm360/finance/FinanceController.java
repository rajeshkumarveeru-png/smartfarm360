package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.*;
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
public class FinanceController {
    private final FinanceService service;

    public FinanceController(FinanceService service) { this.service = service; }

    @GetMapping("/finance/summary")
    @RequiresPermission(Permission.FINANCE_READ)
    public Map<String, Object> summary(TenantContext ctx) { return service.summary(ctx); }

    @GetMapping("/finance/entries")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<Map<String, Object>> entries(TenantContext ctx) { return service.entries(ctx); }

    @PostMapping("/finance/entries")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createEntry(TenantContext ctx, CurrentUser user, @Valid @RequestBody FinanceRequest r) {
        return service.createEntry(ctx, user, r);
    }

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
