package com.smartfarm360.platform.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Billing settings (billing design, sections 5 and 6). The gateway's secrets and the seller's GST details come from
 * environment variables; {@code ProductionSettings} refuses to start the {@code prod} profile without them.
 */
@Component
public class BillingProperties {
    public final String gateway;
    public final String razorpayKeyId;
    public final String razorpayKeySecret;
    public final String webhookSecret;
    public final String sellerName;
    public final String sellerGstin;
    public final String sellerAddress;
    public final String sellerState;
    public final String sac;
    public final String invoicePrefix;
    public final int trialDays;
    public final int graceDays;

    public BillingProperties(@Value("${app.billing.gateway:fake}") String gateway,
                             @Value("${app.billing.razorpay.key-id:}") String razorpayKeyId,
                             @Value("${app.billing.razorpay.key-secret:}") String razorpayKeySecret,
                             @Value("${app.billing.webhook-secret:}") String webhookSecret,
                             @Value("${app.billing.seller.name:}") String sellerName,
                             @Value("${app.billing.seller.gstin:}") String sellerGstin,
                             @Value("${app.billing.seller.address:}") String sellerAddress,
                             @Value("${app.billing.seller.state:}") String sellerState,
                             @Value("${app.billing.sac:998314}") String sac,
                             @Value("${app.billing.invoice-prefix:SF}") String invoicePrefix,
                             @Value("${app.billing.trial-days:30}") int trialDays,
                             @Value("${app.billing.grace-days:14}") int graceDays) {
        this.gateway = gateway.trim().toLowerCase();
        this.razorpayKeyId = razorpayKeyId.trim();
        this.razorpayKeySecret = razorpayKeySecret.trim();
        this.webhookSecret = webhookSecret.trim();
        this.sellerName = sellerName.trim();
        this.sellerGstin = sellerGstin.trim().toUpperCase();
        this.sellerAddress = sellerAddress.trim();
        // The seller's state is the GSTIN's first two digits unless given
        this.sellerState = !sellerState.isBlank() ? sellerState.trim() : this.sellerGstin.length() >= 2 ? this.sellerGstin.substring(0, 2) : "";
        this.sac = sac.trim();
        this.invoicePrefix = invoicePrefix.trim();
        this.trialDays = trialDays;
        this.graceDays = graceDays;
    }
}
