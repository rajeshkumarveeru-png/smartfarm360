package com.smartfarm360.inventory;

import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.inventory.InventoryDtos.*;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase 1 suppliers, stock items and purchases, kept until Phase 4 replaces them with stock movements (ADR 0005).
 * Since Phase 3 every supplier is also a finance party and every purchase is posted as a bill to Stock (1200),
 * so payables and cash stay complete.
 */
@Service
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final MasterDataService master;

    public InventoryService(JdbcTemplate jdbc, DocumentService documents, MasterDataService master) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.master = master;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> suppliers(TenantContext ctx) {
        return jdbc.queryForList("select id,tenant_id as \"tenantId\",name,phone,village,notes,active,created_at as \"createdAt\" from sf_suppliers where tenant_id=? and active=true order by name", ctx.tenantId());
    }

    @Transactional
    public Map<String, Object> createSupplier(TenantContext ctx, SupplierRequest r) {
        long t = ctx.tenantId();
        Long id = jdbc.queryForObject("insert into sf_suppliers(tenant_id,name,phone,village,notes) values(?,?,?,?,?) returning id", Long.class, t, r.name().trim(), r.phone(), r.village(), r.notes());
        master.partyForLegacySupplier(t, id, r.name(), r.phone(), r.village(), r.notes());
        return jdbc.queryForMap("select id,tenant_id as \"tenantId\",name,phone,village,notes,active from sf_suppliers where id=? and tenant_id=?", id, t);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(TenantContext ctx) {
        return jdbc.queryForList("select id,tenant_id as \"tenantId\",name,category,unit,quantity_on_hand as \"quantityOnHand\",reorder_level as \"reorderLevel\",average_unit_cost as \"averageUnitCost\",(quantity_on_hand*average_unit_cost) as \"stockValue\",active from sf_inventory_items where tenant_id=? and active=true order by name", ctx.tenantId());
    }

    @Transactional
    public Map<String, Object> createItem(TenantContext ctx, ItemRequest r) {
        long t = ctx.tenantId();
        Long id = jdbc.queryForObject("insert into sf_inventory_items(tenant_id,name,category,unit,quantity_on_hand,reorder_level,average_unit_cost) values(?,?,?,?,?,?,?) returning id",
                Long.class, t, r.name().trim(), r.category().trim(), r.unit().trim(), r.quantityOnHand(), r.reorderLevel(), r.averageUnitCost());
        return jdbc.queryForMap("select id,tenant_id as \"tenantId\",name,category,unit,quantity_on_hand as \"quantityOnHand\",reorder_level as \"reorderLevel\",average_unit_cost as \"averageUnitCost\" from sf_inventory_items where id=? and tenant_id=?", id, t);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> purchases(TenantContext ctx) {
        return jdbc.queryForList("select p.id,p.supplier_id as \"supplierId\",s.name as \"supplierName\",p.item_id as \"itemId\",i.name as \"itemName\",p.purchase_date as \"purchaseDate\",p.quantity,p.unit_cost as \"unitCost\",p.total_amount as \"totalAmount\",p.paid_amount as \"paidAmount\",(p.total_amount-p.paid_amount) as \"outstandingAmount\",p.notes from sf_purchases p join sf_suppliers s on s.id=p.supplier_id and s.tenant_id=p.tenant_id join sf_inventory_items i on i.id=p.item_id and i.tenant_id=p.tenant_id where p.tenant_id=? order by p.purchase_date desc,p.id desc limit 500", ctx.tenantId());
    }

    @Transactional
    public Map<String, Object> createPurchase(TenantContext ctx, CurrentUser user, PurchaseRequest r) {
        long t = ctx.tenantId();
        BigDecimal total = r.quantity().multiply(r.unitCost()).setScale(2, RoundingMode.HALF_UP);
        if (r.paidAmount().compareTo(total) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paid amount cannot exceed purchase total");
        List<Map<String, Object>> supplier = jdbc.queryForList("select id,name,phone,village,notes from sf_suppliers where id=? and tenant_id=? and active=true", r.supplierId(), t);
        if (supplier.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier does not belong to this tenant");
        var rows = jdbc.queryForList("select id,name,unit,quantity_on_hand,average_unit_cost from sf_inventory_items where id=? and tenant_id=? and active=true for update", r.itemId(), t);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory item does not belong to this tenant");
        Map<String, Object> item = rows.get(0);
        BigDecimal oldQty = (BigDecimal) item.get("quantity_on_hand");
        BigDecimal oldCost = (BigDecimal) item.get("average_unit_cost");
        BigDecimal newQty = oldQty.add(r.quantity());
        BigDecimal newAvg = newQty.signum() == 0 ? BigDecimal.ZERO
                : oldQty.multiply(oldCost).add(r.quantity().multiply(r.unitCost())).divide(newQty, 2, RoundingMode.HALF_UP);
        jdbc.update("update sf_inventory_items set quantity_on_hand=?,average_unit_cost=? where id=? and tenant_id=?", newQty, newAvg, r.itemId(), t);
        Long id = jdbc.queryForObject("insert into sf_purchases(tenant_id,supplier_id,item_id,purchase_date,quantity,unit_cost,total_amount,paid_amount,notes,created_by) values(?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, t, r.supplierId(), r.itemId(), r.purchaseDate(), r.quantity(), r.unitCost(), total, r.paidAmount(), r.notes(), user.email());

        Map<String, Object> s = supplier.get(0);
        long partyId = master.partyForLegacySupplier(t, r.supplierId(), (String) s.get("name"), (String) s.get("phone"), (String) s.get("village"), (String) s.get("notes"));
        documents.recordStockPurchase(ctx, partyId, r.purchaseDate(), (String) item.get("name"), r.quantity(), (String) item.get("unit"),
                r.unitCost(), total, r.paidAmount(), r.financialAccountId());
        return jdbc.queryForMap("select p.id,p.supplier_id as \"supplierId\",s.name as \"supplierName\",p.item_id as \"itemId\",i.name as \"itemName\",p.purchase_date as \"purchaseDate\",p.quantity,p.unit_cost as \"unitCost\",p.total_amount as \"totalAmount\",p.paid_amount as \"paidAmount\",(p.total_amount-p.paid_amount) as \"outstandingAmount\" from sf_purchases p join sf_suppliers s on s.id=p.supplier_id and s.tenant_id=p.tenant_id join sf_inventory_items i on i.id=p.item_id and i.tenant_id=p.tenant_id where p.id=? and p.tenant_id=?", id, t);
    }
}
