package com.smartfarm360.finance;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class FinanceDtos {
    private FinanceDtos() {}

    public record FinanceRequest(@NotBlank @Size(max = 80) String entryType, @NotBlank @Size(max = 80) String category,
                                 @NotBlank @Size(max = 240) String description,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @NotNull LocalDate occurredOn, @Size(max = 20) String paymentStatus, Long farmId,
                                 @Size(max = 100) String referenceNo) {}

    public record SupplierRequest(@NotBlank @Size(max = 140) String name, @Size(max = 30) String phone,
                                  @Size(max = 120) String village, @Size(max = 300) String notes) {}

    public record ItemRequest(@NotBlank @Size(max = 140) String name, @NotBlank @Size(max = 60) String category,
                              @NotBlank @Size(max = 30) String unit,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 11, fraction = 3) BigDecimal quantityOnHand,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 11, fraction = 3) BigDecimal reorderLevel,
                              @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal averageUnitCost) {}

    public record PurchaseRequest(@NotNull Long supplierId, @NotNull Long itemId, @NotNull LocalDate purchaseDate,
                                  @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                                  @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
                                  @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 2) BigDecimal paidAmount,
                                  @Size(max = 300) String notes) {}
}
