package com.smartfarm360.platform.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The GST rules of the billing design (section 6, P6), each on its own. */
class GstTest {
    private static final Gst.Rates RATES = new Gst.Rates(new BigDecimal("9.00"), new BigDecimal("9.00"), new BigDecimal("18.00"));

    @Test
    void aGstinNeedsTheRightShapeAKnownStateAndItsCheckDigit() {
        assertThat(Gst.validGstin("27AAPFU0939F1ZV")).isTrue();   // the GST portal's published example
        assertThat(Gst.validGstin("33ABCDE1234F1Z7")).isTrue();
        assertThat(Gst.validGstin("33ABCDE1234F1Z5")).isFalse();  // wrong check digit
        assertThat(Gst.validGstin("99ABCDE1234F1Z7")).isFalse();  // no such state
        assertThat(Gst.validGstin("33abcde1234f1z7")).isFalse();
        assertThat(Gst.validGstin("33ABCDE1234F1Z")).isFalse();
        assertThat(Gst.validGstin(null)).isFalse();
    }

    @Test
    void theSameStatePaysCgstAndSgstAndAnotherStatePaysIgst() {
        Gst.Taxes tn = Gst.taxes(new BigDecimal("4990"), "33", "33", RATES);
        assertThat(tn.cgst()).isEqualByComparingTo("449.10");
        assertThat(tn.sgst()).isEqualByComparingTo("449.10");
        assertThat(tn.igst()).isEqualByComparingTo("0");
        assertThat(tn.total()).isEqualByComparingTo("5888.20");
        assertThat(tn.intraState()).isTrue();

        Gst.Taxes ka = Gst.taxes(new BigDecimal("4990"), "33", "29", RATES);
        assertThat(ka.igst()).isEqualByComparingTo("898.20");
        assertThat(ka.cgst().add(ka.sgst())).isEqualByComparingTo("0");
        assertThat(ka.total()).isEqualByComparingTo("5888.20");

        // Each tax is rounded on its own, half up
        Gst.Taxes odd = Gst.taxes(new BigDecimal("499.05"), "33", "33", RATES);
        assertThat(odd.cgst()).isEqualByComparingTo("44.91");   // 44.9145
        assertThat(odd.total()).isEqualByComparingTo("588.87");
    }

    @Test
    void anInvoiceFromAPaidTotalAddsUpToExactlyThatTotal() {
        for (String total : new String[]{"5888.20", "588.82", "885.00", "1.00", "2358.82", "999.99"}) {
            for (String buyer : new String[]{"33", "29"}) {
                Gst.Taxes t = InvoiceService.fromTotal(new BigDecimal(total), "33", buyer, RATES);
                assertThat(t.total()).as(total + " in " + buyer).isEqualByComparingTo(total);
                assertThat(t.taxable().signum()).isPositive();
            }
        }
        assertThat(InvoiceService.fromTotal(new BigDecimal("5888.20"), "33", "33", RATES).taxable()).isEqualByComparingTo("4990.00");
    }

    @Test
    void aCreditNoteSharesEachTaxAndTotalsTheRefund() {
        Gst.Taxes inv = Gst.taxes(new BigDecimal("4990"), "33", "33", RATES);
        Gst.Taxes half = Gst.share(inv, new BigDecimal("2944.10"));
        assertThat(half.total()).isEqualByComparingTo("2944.10");
        assertThat(half.cgst()).isEqualByComparingTo("224.55");
        assertThat(half.taxable()).isEqualByComparingTo("2495.00");
        assertThat(Gst.share(inv, inv.total()).taxable()).isEqualByComparingTo("4990.00");
    }

    @Test
    void numbersRunPerFinancialYearFromApril() {
        assertThat(Gst.financialYear(LocalDate.of(2026, 11, 28))).isEqualTo("2026-27");
        assertThat(Gst.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(Gst.financialYear(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(Gst.financialYear(LocalDate.of(2099, 6, 1))).isEqualTo("2099-00");
        assertThat(Gst.number("SF", "2026-27", 41)).isEqualTo("SF/2026-27/000041");
    }

    @Test
    void theEffectivePlanFollowsTheStateAndAComp() {
        java.time.Instant now = java.time.Instant.parse("2026-11-28T00:00:00Z");
        SubscriptionService.Sub trial = sub("TRIAL", now.plusSeconds(3600), null, null, null);
        assertThat(SubscriptionService.effectivePlan(trial, now)).isEqualTo("GROWER");
        assertThat(SubscriptionService.effectivePlan(trial, now.plusSeconds(7200))).isEqualTo("FREE");
        assertThat(SubscriptionService.effectivePlan(sub("PAST_DUE", null, null, null, null), now)).isEqualTo("GROWER");
        assertThat(SubscriptionService.effectivePlan(sub("LAPSED", null, null, null, null), now)).isEqualTo("FREE");
        assertThat(SubscriptionService.effectivePlan(sub("CANCELLED", null, now.plusSeconds(60), null, null), now)).isEqualTo("GROWER");
        assertThat(SubscriptionService.effectivePlan(sub("CANCELLED", null, now.minusSeconds(60), null, null), now)).isEqualTo("FREE");
        assertThat(SubscriptionService.effectivePlan(sub("ENDED", null, null, "ESTATE", now.plusSeconds(60)), now)).isEqualTo("ESTATE");
        assertThat(SubscriptionService.effectivePlan(sub("ENDED", null, null, "ESTATE", now.minusSeconds(60)), now)).isEqualTo("FREE");
    }

    private static SubscriptionService.Sub sub(String state, java.time.Instant trialEnds, java.time.Instant paidUntil, String comp, java.time.Instant compUntil) {
        return new SubscriptionService.Sub(1, 1, "GROWER", "MONTHLY", state, "FREE", trialEnds, null, null, null, paidUntil, null, null, null, null, null,
                null, null, null, null, null, null, null, comp, compUntil, null, null);
    }
}
