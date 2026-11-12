package com.smartfarm360.livestock;

import com.smartfarm360.livestock.HatcheryDtos.*;
import com.smartfarm360.livestock.LivestockDtos.Group;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Incubation batches and hatch outcomes. Hatched chicks enter the linked flock as a dated BORN movement. */
@Service
public class HatcheryService {
    private final JdbcClient jdbc;
    private final FlockService flocks;
    private final AuditService audit;

    public HatcheryService(JdbcClient jdbc, FlockService flocks, AuditService audit) {
        this.jdbc = jdbc; this.flocks = flocks; this.audit = audit;
    }

    public List<Hatch> list(TenantContext ctx, long groupId) {
        Group group = flocks.counted(ctx, groupId);
        requirePoultry(group);
        return jdbc.sql("""
            select h.id,h.group_id,h.line_id,l.variety,h.collected_on,h.incubated_on,h.expected_on,h.eggs,
                   h.fertile,h.hatched,h.failed,h.result_on,h.notes,(h.result_on is not null) completed
            from sf_poultry_hatches h join sf_flock_lines l on l.tenant_id=h.tenant_id and l.id=h.line_id
            where h.tenant_id=:t and h.group_id=:g order by h.incubated_on desc,h.id desc
            """).param("t",ctx.tenantId()).param("g",groupId).query((rs,i)->new Hatch(
                rs.getLong("id"),rs.getLong("group_id"),rs.getLong("line_id"),rs.getString("variety"),
                rs.getObject("collected_on",LocalDate.class),rs.getObject("incubated_on",LocalDate.class),
                rs.getObject("expected_on",LocalDate.class),rs.getInt("eggs"),
                (Integer)rs.getObject("fertile"),(Integer)rs.getObject("hatched"),(Integer)rs.getObject("failed"),
                rs.getObject("result_on",LocalDate.class),rs.getString("notes"),rs.getBoolean("completed"))).list();
    }

    @Transactional
    public Hatch create(TenantContext ctx, long groupId, CreateRequest r) {
        Group group=flocks.counted(ctx,groupId); requirePoultry(group);
        if (r.collectedOn().isAfter(LocalDate.now()) || r.incubatedOn().isAfter(LocalDate.now()))
            throw ApiException.badRequest("FUTURE_DATE","Collection and incubation dates cannot be in the future.");
        if (r.incubatedOn().isBefore(r.collectedOn()) || (r.expectedOn()!=null && r.expectedOn().isBefore(r.incubatedOn())))
            throw ApiException.badRequest("HATCH_DATE_ORDER","Check the collection, incubation and expected hatch dates.");
        Integer exists=jdbc.sql("select count(*) from sf_flock_lines where tenant_id=:t and group_id=:g and id=:l")
            .param("t",ctx.tenantId()).param("g",groupId).param("l",r.lineId()).query(Integer.class).single();
        if (exists==0) throw ApiException.badRequest("FLOCK_LINE_NOT_FOUND","Choose a variety from this batch.");
        long id=jdbc.sql("""
            insert into sf_poultry_hatches(tenant_id,group_id,line_id,collected_on,incubated_on,expected_on,eggs,notes,created_by)
            values(:t,:g,:l,:c,:i,:e,:eg,:n,:u) returning id
            """).param("t",ctx.tenantId()).param("g",groupId).param("l",r.lineId()).param("c",r.collectedOn())
            .param("i",r.incubatedOn()).param("e",r.expectedOn()).param("eg",r.eggs()).param("n",r.notes()).param("u",ctx.userId())
            .query(Long.class).single();
        audit.record(ctx.tenantId(),ctx.userId(),"POULTRY_HATCH_CREATED","POULTRY_HATCH",id,Map.of("groupId",groupId,"eggs",r.eggs()));
        return list(ctx,groupId).stream().filter(h->h.id()==id).findFirst().orElseThrow();
    }

    @Transactional
    public Hatch result(TenantContext ctx,long hatchId,ResultRequest r) {
        Map<String,Object> row=jdbc.sql("select group_id,line_id,eggs,incubated_on,result_on from sf_poultry_hatches where tenant_id=:t and id=:id for update")
            .param("t",ctx.tenantId()).param("id",hatchId).query().listOfRows().stream().findFirst()
            .orElseThrow(()->ApiException.notFound("HATCH_NOT_FOUND","Hatching batch not found."));
        long groupId=((Number)row.get("group_id")).longValue(), lineId=((Number)row.get("line_id")).longValue();
        int eggs=((Number)row.get("eggs")).intValue();
        if(row.get("result_on")!=null) throw ApiException.conflict("HATCH_ALREADY_COMPLETED","This hatching batch already has a result.");
        if(r.resultOn().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE","Hatch result date cannot be in the future.");
        LocalDate incubatedOn=(LocalDate)row.get("incubated_on");
        if(r.resultOn().isBefore(incubatedOn)) throw ApiException.badRequest("HATCH_DATE_ORDER","Hatch result cannot be before incubation.");
        if(r.fertile()>eggs || r.hatched()>r.fertile() || r.failed()>eggs-r.hatched())
            throw ApiException.badRequest("HATCH_COUNTS_INVALID","Fertile, hatched and failed counts must fit the incubated egg count.");
        jdbc.sql("update sf_poultry_hatches set fertile=:f,hatched=:h,failed=:x,result_on=:d,notes=coalesce(:n,notes) where tenant_id=:t and id=:id")
            .param("f",r.fertile()).param("h",r.hatched()).param("x",r.failed()).param("d",r.resultOn()).param("n",r.notes())
            .param("t",ctx.tenantId()).param("id",hatchId).update();
        if(r.hatched()>0) jdbc.sql("""
            insert into sf_head_movements(tenant_id,group_id,move_date,kind,heads,line_id,cause,created_by)
            values(:t,:g,:d,'BORN',:h,:l,:c,:u)
            """).param("t",ctx.tenantId()).param("g",groupId).param("d",r.resultOn()).param("h",r.hatched()).param("l",lineId)
            .param("c","Hatched chicks · hatch batch #"+hatchId).param("u",ctx.userId()).update();
        audit.record(ctx.tenantId(),ctx.userId(),"POULTRY_HATCH_RECORDED","POULTRY_HATCH",hatchId,Map.of("hatched",r.hatched()));
        return list(ctx,groupId).stream().filter(h->h.id()==hatchId).findFirst().orElseThrow();
    }

    private static void requirePoultry(Group group) {
        if(!"CHICKEN".equals(group.species())) throw ApiException.badRequest("NOT_POULTRY","Hatching is available for poultry batches only.");
    }
}
