package com.smartfarm360.assets;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.ReversalGuard;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the register and the ledger in step when a record is reversed: depreciation runs are reversed latest first and
 * before any disposal of their assets; a purchase or opening balance whose cost is registered cannot be reversed.
 */
@Component
class AssetReversalGuard implements ReversalGuard {
    private final JdbcClient jdbc;

    AssetReversalGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void checkReversal(TenantContext ctx, long documentId, DocType type) {
        long t = ctx.tenantId();
        if (type == DocType.DEPRECIATION) {
            boolean later = jdbc.sql("""
                            select exists (select 1 from sf_asset_depreciation x join sf_asset_depreciation y on y.asset_id = x.asset_id and y.month > x.month
                              join sf_finance_documents d on d.id = y.document_id where x.document_id = :d and x.tenant_id = :t and d.status = 'POSTED')""")
                    .param("d", documentId).param("t", t).query(Boolean.class).single();
            if (later) throw ApiException.conflict("DEPRECIATION_LATER_MONTHS", "Reverse the later depreciation runs first.");
            boolean disposed = jdbc.sql("""
                            select exists (select 1 from sf_asset_depreciation x join sf_assets a on a.id = x.asset_id
                              join sf_finance_documents d on d.id = a.disposal_document_id where x.document_id = :d and x.tenant_id = :t and d.status = 'POSTED')""")
                    .param("d", documentId).param("t", t).query(Boolean.class).single();
            if (disposed) throw ApiException.conflict("ASSET_DISPOSED", "An asset in this run is sold or scrapped. Reverse the disposal first.");
        }
        if (type == DocType.BILL || type == DocType.OPENING_BALANCE) {
            boolean registered = jdbc.sql("""
                            select exists (select 1 from sf_assets a join sf_finance_document_lines l on l.id = a.source_line_id
                              where l.document_id = :d and a.tenant_id = :t)""")
                    .param("d", documentId).param("t", t).query(Boolean.class).single();
            if (registered) throw ApiException.conflict("ASSET_REGISTERED", "This record bought an asset in the register. Remove the asset first.");
        }
    }
}
