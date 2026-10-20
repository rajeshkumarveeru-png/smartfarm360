package com.smartfarm360.finance;

import com.smartfarm360.platform.security.TenantContext;

/**
 * Lets the module that built a record refuse its reversal while later records depend on it (a pay run already paid, a
 * depreciation run followed by later months or a disposal). Implemented outside finance, so finance depends on no module.
 */
public interface ReversalGuard {
    void checkReversal(TenantContext ctx, long documentId, DocType type);
}
