package com.smartfarm360.processing;

import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.processing.ProcessingDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * The lot trace (processing and trading design, M8): from stock movements and lots, backward from a lot to the batch that
 * made it, its input lots and their crop cycle, herd or supplier; forward to the batches that used it, their output lots
 * and the customers. Only posted records count.
 */
@Service
public class TraceService {
    private static final int DEPTH = 4;
    private final JdbcClient jdbc;

    public TraceService(JdbcClient jdbc) { this.jdbc = jdbc; }

    private record LotRow(long id, String lotNo, long itemId, String itemName, String baseUnit) {}

    private LotRow lot(long tenantId, long lotId) {
        return jdbc.sql("""
                        select l.id, l.lot_no, l.item_id, i.name, i.base_unit_code from sf_stock_lots l join sf_stock_items i on i.id = l.item_id and i.tenant_id = l.tenant_id
                        where l.tenant_id = :t and l.id = :id""")
                .param("t", tenantId).param("id", lotId)
                .query((rs, i) -> new LotRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5))).single();
    }

    /** Every lot with this number (one per item), traced both ways. */
    @Transactional(readOnly = true)
    public List<TracedLot> trace(TenantContext ctx, String lotNo) {
        if (lotNo == null || lotNo.isBlank()) throw ApiException.badRequest("LOT_REQUIRED", "Enter a lot number.");
        List<Long> ids = jdbc.sql("select id from sf_stock_lots where tenant_id = :t and upper(lot_no) = upper(:n) order by id")
                .param("t", ctx.tenantId()).param("n", lotNo.trim()).query(Long.class).list();
        if (ids.isEmpty()) throw ApiException.notFound("LOT_NOT_FOUND", "No lot with this number.");
        List<TracedLot> out = new ArrayList<>();
        for (long id : ids) {
            TracedLot back = backward(ctx.tenantId(), id, DEPTH);
            TracedLot fwd = forward(ctx.tenantId(), id, DEPTH);
            out.add(new TracedLot(back.lotId(), back.lotNo(), back.itemId(), back.itemName(), back.baseUnit(), back.origin(), back.inputs(), fwd.usedIn(), fwd.sales()));
        }
        return out;
    }

    /** Where the lot came from: the first posted record that put it into stock. */
    private Origin origin(long tenantId, long lotId) {
        return jdbc.sql("""
                        select m.movement_type, d.doc_date, d.number, m.cost_object_id, co.name, co.kind, p.name, b.code
                        from sf_stock_movements m
                        join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        left join sf_cost_objects co on co.id = m.cost_object_id and co.tenant_id = m.tenant_id
                        left join sf_parties p on p.id = d.party_id and p.tenant_id = d.tenant_id
                        left join sf_processing_batches b on b.cost_object_id = m.cost_object_id and b.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.lot_id = :l and m.quantity > 0 and d.status = 'POSTED'
                          and m.movement_type in ('PRODUCE', 'PURCHASE', 'OPENING', 'COUNT_GAIN', 'TRANSFER_IN', 'CUSTOMER_RETURN')
                        order by m.movement_date, m.id limit 1""")
                .param("t", tenantId).param("l", lotId)
                .query((rs, i) -> {
                    String type = rs.getString(1);
                    String batch = rs.getString(8);
                    String kind = batch != null ? "BATCH" : "PRODUCE".equals(type) ? "PRODUCED" : "PURCHASE".equals(type) ? "BOUGHT" : "OTHER";
                    return new Origin(kind, rs.getObject(2, LocalDate.class), rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), batch);
                }).optional().orElse(new Origin("OTHER", null, null, null, null, null, null, null));
    }

    private TracedLot backward(long tenantId, long lotId, int depth) {
        LotRow l = lot(tenantId, lotId);
        Origin o = origin(tenantId, lotId);
        List<TracedLot> inputs = new ArrayList<>();
        if ("BATCH".equals(o.kind()) && depth > 0) {
            for (long input : lotsUsedBy(tenantId, o.costObjectId())) inputs.add(backward(tenantId, input, depth - 1));
        }
        return new TracedLot(l.id(), l.lotNo(), l.itemId(), l.itemName(), l.baseUnit(), o, inputs, List.of(), List.of());
    }

    /** Input lots a batch used (posted stock uses on its cost object). */
    private List<Long> lotsUsedBy(long tenantId, long costObjectId) {
        return jdbc.sql("""
                        select m.lot_id from sf_stock_movements m join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'ISSUE' and m.lot_id is not null and d.status = 'POSTED'
                        group by m.lot_id having sum(m.quantity) < 0 order by min(m.id)""")
                .param("t", tenantId).param("co", costObjectId).query(Long.class).list();
    }

    private TracedLot forward(long tenantId, long lotId, int depth) {
        LotRow l = lot(tenantId, lotId);
        List<LotUse> used = new ArrayList<>();
        if (depth > 0) {
            for (Map<String, Object> u : jdbc.sql("""
                            select b.id, b.code, co.name, sum(-m.quantity) as qty, b.cost_object_id
                            from sf_stock_movements m
                            join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                            join sf_processing_batches b on b.cost_object_id = m.cost_object_id and b.tenant_id = m.tenant_id
                            join sf_cost_objects co on co.id = b.cost_object_id and co.tenant_id = b.tenant_id
                            where m.tenant_id = :t and m.lot_id = :l and m.movement_type = 'ISSUE' and d.status = 'POSTED'
                            group by b.id, b.code, co.name, b.cost_object_id having sum(-m.quantity) > 0 order by b.id""")
                    .param("t", tenantId).param("l", lotId).query().listOfRows()) {
                long co = ((Number) u.get("cost_object_id")).longValue();
                List<TracedLot> outputs = new ArrayList<>();
                for (long out : jdbc.sql("""
                                select m.lot_id from sf_stock_movements m join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                                where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'PRODUCE' and m.lot_id is not null and d.status = 'POSTED'
                                group by m.lot_id order by min(m.id)""")
                        .param("t", tenantId).param("co", co).query(Long.class).list()) {
                    outputs.add(forward(tenantId, out, depth - 1));
                }
                used.add(new LotUse((String) u.get("code"), (String) u.get("name"), ((Number) u.get("id")).longValue(), (BigDecimal) u.get("qty"), outputs));
            }
        }
        List<Sale> sales = jdbc.sql("""
                        select d.doc_date, d.number, p.name, sum(-m.quantity)
                        from sf_stock_movements m
                        join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        left join sf_parties p on p.id = d.party_id and p.tenant_id = d.tenant_id
                        where m.tenant_id = :t and m.lot_id = :l and m.movement_type = 'SALE' and d.status = 'POSTED'
                        group by d.id, d.doc_date, d.number, p.name having sum(-m.quantity) > 0 order by d.doc_date, d.id""")
                .param("t", tenantId).param("l", lotId)
                .query((rs, i) -> new Sale(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4))).list();
        return new TracedLot(l.id(), l.lotNo(), l.itemId(), l.itemName(), l.baseUnit(), null, List.of(), used, sales);
    }
}
