package com.smartfarm360.platform.billing;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The payment gateway's webhook (billing design, section 5). Open to the internet, so it trusts nothing until the
 * signature over the exact body checks out; it finds the farm business by the gateway's ids, never by ids in the body.
 */
@RestController
@RequestMapping("/api/v1/billing/webhooks")
public class BillingWebhookController {
    private final WebhookService webhooks;

    public BillingWebhookController(WebhookService webhooks) { this.webhooks = webhooks; }

    @PostMapping(value = "/{gateway}", consumes = "application/json")
    public Map<String, String> receive(@PathVariable String gateway, @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId, @RequestBody String body) {
        return Map.of("outcome", webhooks.receive(gateway, eventId, signature, body));
    }
}
