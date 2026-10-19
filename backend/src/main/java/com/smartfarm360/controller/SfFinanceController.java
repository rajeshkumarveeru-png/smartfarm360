package com.smartfarm360.controller;

import com.smartfarm360.service.SfFinanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/smartfarm/tenants/{tenantId}")
public class SfFinanceController {
    private final SfFinanceService service;
    public SfFinanceController(SfFinanceService service) { this.service = service; }

    public record FinanceRequest(@NotBlank @Size(max=80) String entryType, @NotBlank @Size(max=80) String category,
        @NotBlank @Size(max=240) String description, @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate occurredOn, @Size(max=20) String paymentStatus, Long farmId, @Size(max=100) String referenceNo) {}
    public record SupplierRequest(@NotBlank @Size(max=140) String name, @Size(max=30) String phone,
        @Size(max=120) String village, @Size(max=300) String notes) {}
    public record ItemRequest(@NotBlank @Size(max=140) String name, @NotBlank @Size(max=60) String category,
        @NotBlank @Size(max=30) String unit, @NotNull @DecimalMin("0.0") BigDecimal quantityOnHand,
        @NotNull @DecimalMin("0.0") BigDecimal reorderLevel, @NotNull @DecimalMin("0.0") BigDecimal averageUnitCost) {}
    public record PurchaseRequest(@NotNull Long supplierId, @NotNull Long itemId, @NotNull LocalDate purchaseDate,
        @NotNull @DecimalMin("0.001") BigDecimal quantity, @NotNull @DecimalMin("0.0") BigDecimal unitCost,
        @NotNull @DecimalMin("0.0") BigDecimal paidAmount, @Size(max=300) String notes) {}

    @GetMapping("/finance/summary") public Map<String,Object> summary(Authentication a,@PathVariable Long tenantId){return service.summary(a.getName(),tenantId);}
    @GetMapping("/finance/entries") public List<Map<String,Object>> entries(Authentication a,@PathVariable Long tenantId){return service.entries(a.getName(),tenantId);}
    @PostMapping("/finance/entries") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createEntry(Authentication a,@PathVariable Long tenantId,@Valid @RequestBody FinanceRequest r){return service.createEntry(a.getName(),tenantId,r);}
    @GetMapping("/suppliers") public List<Map<String,Object>> suppliers(Authentication a,@PathVariable Long tenantId){return service.suppliers(a.getName(),tenantId);}
    @PostMapping("/suppliers") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createSupplier(Authentication a,@PathVariable Long tenantId,@Valid @RequestBody SupplierRequest r){return service.createSupplier(a.getName(),tenantId,r);}
    @GetMapping("/inventory/items") public List<Map<String,Object>> items(Authentication a,@PathVariable Long tenantId){return service.items(a.getName(),tenantId);}
    @PostMapping("/inventory/items") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createItem(Authentication a,@PathVariable Long tenantId,@Valid @RequestBody ItemRequest r){return service.createItem(a.getName(),tenantId,r);}
    @GetMapping("/purchases") public List<Map<String,Object>> purchases(Authentication a,@PathVariable Long tenantId){return service.purchases(a.getName(),tenantId);}
    @PostMapping("/purchases") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createPurchase(Authentication a,@PathVariable Long tenantId,@Valid @RequestBody PurchaseRequest r){return service.createPurchase(a.getName(),tenantId,r);}
}
