package com.smartfarm360.inventory;

import com.smartfarm360.inventory.TradingController.TradingRow;
import com.smartfarm360.platform.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Trading margin by item for goods bought for resale (processing and trading design, M10), from posted stock movements and sales. */
@Service
public class TradingReportService {
    private final JdbcClient jdbc;

    public TradingReportService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<TradingRow> rows(TenantContext ctx, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select i.id, i.name, i.base_unit_code,
                          coalesce(sum(m.quantity) filter (where m.movement_type = 'PURCHASE' and m.movement_date between :from and :to and d.status = 'POSTED'), 0),
                          coalesce(sum(m.value) filter (where m.movement_type = 'PURCHASE' and m.movement_date between :from and :to and d.status = 'POSTED'), 0),
                          coalesce(sum(-m.quantity) filter (where m.movement_type = 'SALE' and m.movement_date between :from and :to and d.status = 'POSTED'), 0),
                          coalesce((select sum(l.amount) from sf_finance_document_lines l join sf_finance_documents sd on sd.id = l.document_id and sd.tenant_id = l.tenant_id
                                    where l.tenant_id = i.tenant_id and l.item_id = i.id and sd.doc_type = 'SALE' and sd.status = 'POSTED'
                                      and sd.doc_date between :from and :to), 0),
                          coalesce(sum(-m.value) filter (where m.movement_type = 'SALE' and m.movement_date between :from and :to and d.status = 'POSTED'), 0),
                          coalesce(sum(m.quantity) filter (where m.movement_date <= :to), 0),
                          coalesce(sum(m.value) filter (where m.movement_date <= :to), 0)
                        from sf_stock_items i
                        left join sf_stock_movements m on m.item_id = i.id and m.tenant_id = i.tenant_id
                        left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where i.tenant_id = :t and i.kind = 'RESALE'
                        group by i.id, i.name, i.base_unit_code, i.tenant_id order by i.name""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, n) -> {
                    BigDecimal revenue = rs.getBigDecimal(7), cogs = rs.getBigDecimal(8), margin = revenue.subtract(cogs);
                    return new TradingRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), revenue, cogs,
                            margin, revenue.signum() == 0 ? null : margin.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP),
                            rs.getBigDecimal(9), rs.getBigDecimal(10));
                }).list().stream()
                .filter(r -> r.boughtQuantity().signum() != 0 || r.soldQuantity().signum() != 0 || r.stockQuantity().signum() != 0).toList();
    }
}
