package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A farm business's plan and billing (billing design, sections 3–8). Every member sees the plan and its banner;
 * accountants also see invoices; only owners pay, change and cancel. Payment itself happens on the gateway's page.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/billing")
public class BillingController {
    private final SubscriptionService subscriptions;
    private final BillingService billing;

    public BillingController(SubscriptionService subscriptions, BillingService billing) {
        this.subscriptions = subscriptions;
        this.billing = billing;
    }

    public record ProfileRequest(@NotBlank @Size(max = 140) String legalName, @NotBlank @Size(max = 200) String addressLine1,
                                 @Size(max = 200) String addressLine2, @NotBlank @Size(max = 80) String city,
                                 @NotNull @Pattern(regexp = "[1-9][0-9]{5}") String pincode, @NotNull @Pattern(regexp = "[0-9]{2}") String stateCode,
                                 @Size(max = 15) String gstin, @NotBlank @Email @Size(max = 254) String email) {}

    public record PlanRequest(@NotNull @Size(max = 30) String planCode, @NotNull @Pattern(regexp = "MONTHLY|YEARLY") String period) {}

    @GetMapping
    @RequiresPermission(Permission.TENANT_READ)
    public SubscriptionService.Overview overview(TenantContext ctx) { return subscriptions.overview(ctx.tenantId()); }

    @GetMapping("/profile")
    @RequiresPermission(Permission.BILLING_VIEW)
    public ResponseEntity<BillingService.Profile> profile(TenantContext ctx) {
        return billing.profile(ctx).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/profile")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public BillingService.Profile saveProfile(TenantContext ctx, @Valid @RequestBody ProfileRequest r) {
        return billing.saveProfile(ctx, new BillingService.Profile(r.legalName(), r.addressLine1(), r.addressLine2(), r.city(), r.pincode(), r.stateCode(),
                r.gstin(), r.email(), null));
    }

    /** Starts paying for a plan; the answer's {@code checkoutUrl} is the gateway's page. */
    @PostMapping("/checkout")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.CheckoutResult checkout(TenantContext ctx, @Valid @RequestBody PlanRequest r) {
        return subscriptions.checkout(ctx, r.planCode(), r.period());
    }

    /** Changes the plan: an upgrade answers with the gateway's page for its pro-rata part; anything else applies at renewal. */
    @PostMapping("/change")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.CheckoutResult change(TenantContext ctx, @Valid @RequestBody PlanRequest r) {
        return subscriptions.change(ctx, r.planCode(), r.period());
    }

    @PostMapping("/cancel")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.Overview cancel(TenantContext ctx) { return subscriptions.cancel(ctx); }

    @GetMapping("/invoices")
    @RequiresPermission(Permission.BILLING_VIEW)
    public List<InvoiceService.Invoice> invoices(TenantContext ctx) { return billing.invoices(ctx); }

    @GetMapping("/invoices/{invoiceId}/pdf")
    @RequiresPermission(Permission.BILLING_VIEW)
    public ResponseEntity<byte[]> invoicePdf(TenantContext ctx, @PathVariable long invoiceId) { return pdf(billing.invoicePdf(ctx, invoiceId)); }

    @GetMapping("/invoices/{invoiceId}/credit-notes/{creditNoteId}/pdf")
    @RequiresPermission(Permission.BILLING_VIEW)
    public ResponseEntity<byte[]> creditNotePdf(TenantContext ctx, @PathVariable long invoiceId, @PathVariable long creditNoteId) {
        return pdf(billing.creditNotePdf(ctx, invoiceId, creditNoteId));
    }

    private static ResponseEntity<byte[]> pdf(BillingService.Pdf pdf) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(pdf.content());
    }
}
