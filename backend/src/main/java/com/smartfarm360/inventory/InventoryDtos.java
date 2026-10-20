package com.smartfarm360.inventory;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class InventoryDtos {
    private InventoryDtos() {}

    public record SupplierRequest(@NotBlank @Size(max = 140) String name, @Size(max = 30) String phone,
                                  @Size(max = 120) String village, @Size(max = 300) String notes) {}

    public record ItemRequest(@NotBlank @Size(max = 140) String name, @NotBlank @Size(max = 60) String category,
                              @NotBlank @Size(max = 30) String unit,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 11, fraction = 3) BigDecimal quantityOnHand,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 11, fraction = 3) BigDecimal reorderLevel,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal averageUnitCost) {}

    /** {@code financialAccountId}: where the paid part came from; defaults to the tenant's first cash account. */
    public record PurchaseRequest(@NotNull Long supplierId, @NotNull Long itemId, @NotNull LocalDate purchaseDate,
                                  @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                                  @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
                                  @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal paidAmount,
                                  Long financialAccountId, @Size(max = 300) String notes) {}
}
