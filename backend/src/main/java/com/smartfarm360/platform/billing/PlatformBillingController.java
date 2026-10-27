package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The platform console's Billing tab (billing design, section 9, P12). Platform administrators who signed in with the
 * second step only (SecurityConfig, WebMvcConfig). Every change needs a reason and goes to the platform audit log.
 */
@RestController
@RequestMapping("/api/v1/platform/billing")
public class PlatformBillingController {
    private final SubscriptionService subscriptions;
    private final BillingService billing;
    private final InvoiceService invoices;
    private final WebhookService webhooks;
    private final ReconciliationService reconciliation;

    public PlatformBillingController(SubscriptionService subscriptions, BillingService billing, InvoiceService invoices, WebhookService webhooks,
                                     ReconciliationService reconciliation) {
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.invoices = invoices;
        this.webhooks = webhooks;
        this.reconciliation = reconciliation;
    }

    public record DaysRequest(@NotNull @Min(1) @Max(90) Integer days, @NotBlank @Size(max = 300) String reason) {}

    public record CompRequest(@NotNull @Size(max = 30) String planCode, @NotNull LocalDate until, @NotBlank @Size(max = 300) String reason) {}

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {}

    public record RefundRequest(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @NotBlank @Size(max = 300) String reason) {}

    public record PriceRequest(@NotNull @Size(max = 30) String planCode, @NotNull @Pattern(regexp = "MONTHLY|YEARLY") String period,
                               @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @NotNull LocalDate validFrom) {}

    public record Summary(Map<String, Long> subscriptions, List<WebhookService.StoredEvent> unappliedEvents, ReconciliationService.Run lastReconciliation) {}

    @GetMapping
    public Summary summary() {
        List<ReconciliationService.Run> runs = reconciliation.runs(1);
        return new Summary(billing.countsByState(), webhooks.unapplied(), runs.isEmpty() ? null : runs.get(0));
    }

    @GetMapping("/subscriptions")
    public List<SubscriptionService.ConsoleSubscription> subscriptions(@RequestParam(required = false) String state) { return subscriptions.consoleList(state); }

    @GetMapping("/payments")
    public List<BillingService.PaymentRow> payments(@RequestParam(required = false) Long tenantId) { return billing.payments(tenantId); }

    @GetMapping("/invoices")
    public List<InvoiceService.InvoiceRow> invoices() { return invoices.recent(200); }

    @PostMapping("/invoices/{invoiceId}/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend(CurrentUser admin, @PathVariable long invoiceId) { billing.resendInvoice(admin, invoiceId); }

    @PostMapping("/payments/{paymentId}/refund")
    public InvoiceService.CreditNote refund(CurrentUser admin, @PathVariable long paymentId, @Valid @RequestBody RefundRequest r) {
        return billing.refund(admin, paymentId, r.amount(), r.reason().trim());
    }

    @PostMapping("/tenants/{id}/trial")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extendTrial(CurrentUser admin, @PathVariable("id") long tenantId, @Valid @RequestBody DaysRequest r) {
        subscriptions.extendTrial(admin, tenantId, r.days(), r.reason().trim());
    }

    @PostMapping("/tenants/{id}/grace")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extendGrace(CurrentUser admin, @PathVariable("id") long tenantId, @Valid @RequestBody DaysRequest r) {
        subscriptions.extendGrace(admin, tenantId, r.days(), r.reason().trim());
    }

    @PutMapping("/tenants/{id}/comp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void comp(CurrentUser admin, @PathVariable("id") long tenantId, @Valid @RequestBody CompRequest r) {
        subscriptions.comp(admin, tenantId, r.planCode(), r.until(), r.reason().trim());
    }

    @PostMapping("/tenants/{id}/comp/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endComp(CurrentUser admin, @PathVariable("id") long tenantId, @Valid @RequestBody ReasonRequest r) {
        subscriptions.endComp(admin, tenantId, r.reason().trim());
    }

    @GetMapping("/prices")
    public List<BillingService.PriceRow> prices() { return billing.prices(); }

    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.PriceRow setPrice(CurrentUser admin, @Valid @RequestBody PriceRequest r) {
        return billing.setPrice(admin, r.planCode(), r.period(), r.amount(), r.validFrom());
    }

    @GetMapping("/reconciliation")
    public List<ReconciliationService.Run> reconciliationRuns() { return reconciliation.runs(30); }

    @PostMapping("/reconciliation")
    public ReconciliationService.Run reconcile() { return reconciliation.runNow(); }

    @PostMapping("/events/{eventId}/replay")
    public WebhookService.StoredEvent replay(CurrentUser admin, @PathVariable long eventId) { return webhooks.replay(admin, eventId); }
}
