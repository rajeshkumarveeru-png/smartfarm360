package com.smartfarm360.platform.bin;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Bin (bin and batch varieties design, B1-B9): delete, restore and delete forever, with the rules that keep the books right. */
class BinTest extends FinanceTestSupport {

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    private List<String> binNames() throws Exception {
        JsonNode items = body(get(t("/bin"), owner).andExpect(status().isOk()));
        return StreamSupport.stream(items.spliterator(), false).map(n -> n.get("kind").asText() + ":" + n.get("name").asText()).toList();
    }

    private List<Long> farmIds() throws Exception {
        return StreamSupport.stream(body(get(t("/farms"), owner)).spliterator(), false).map(n -> n.get("id").asLong()).toList();
    }

    private long batch(String name) throws Exception {
        return body(post(t("/livestock/flocks"), owner, Map.of("name", name, "species", "CHICKEN", "purpose", "COUNTRY", "placedOn", "2026-07-01", "heads", 12))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void aFarmGoesToTheBinComesBackAndIsDeletedForever() throws Exception {
        newFinanceTenant("bin-" + UUID.randomUUID().toString().substring(0, 6));
        long farm = newFarm(owner, tenant, "Mistake farm");
        long field = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "Corner", "area", 1))).get("id").asLong();

        delete(t("/farms/" + farm), owner).andExpect(status().isNoContent());
        assertThat(farmIds()).doesNotContain(farm);
        assertThat(binNames()).contains("FARM:Mistake farm");
        delete(t("/farms/" + farm), owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BIN_ALREADY"));

        post(t("/bin/FARM/" + farm + "/restore"), owner, Map.of()).andExpect(status().isNoContent());
        assertThat(farmIds()).contains(farm);
        assertThat(binNames()).doesNotContain("FARM:Mistake farm");
        delete(t("/bin/FARM/" + farm), owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BIN_NOT_IN_BIN"));

        // A field cannot come back while its farm is in the Bin
        delete(t("/land-parcels/" + field), owner).andExpect(status().isNoContent());
        post(t("/bin/FARM/" + farm), owner, Map.of()).andExpect(status().isNoContent());
        post(t("/bin/LAND_PARCEL/" + field + "/restore"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BIN_PARENT_DELETED"));

        delete(t("/bin/FARM/" + farm), owner).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from sf_farms where id = ?", Long.class, farm)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_land_parcels where id = ?", Long.class, field)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_audit_history where tenant_id = ? and action in ('BIN_DELETED', 'BIN_RESTORED', 'BIN_PURGED')",
                Long.class, tenant)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void anItemWithMoneyIsKeptInTheBinAndTheAccountsDoNotChange() throws Exception {
        newFinanceTenant("bin-kept-" + UUID.randomUUID().toString().substring(0, 6));
        long farm = newFarm(owner, tenant, "Paid farm");
        post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash, "farmId", farm, "lines", List.of(line("SEEDS", 250, null))))
                .andExpect(status().isCreated());
        delete(t("/farms/" + farm), owner).andExpect(status().isNoContent());
        delete(t("/bin/FARM/" + farm), owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BIN_KEPT"));
        assertThat(binNames()).contains("FARM:Paid farm");
        money(report("income-statement?from=2026-07-01&to=2026-07-31&farmId=" + farm).get("operatingCostTotal"), "250");
    }

    @Test
    void aPoultryBatchGoesToTheBinAndIsDeletedForeverUnlessABillIsBookedToIt() throws Exception {
        newFinanceTenant("bin-batch-" + UUID.randomUUID().toString().substring(0, 6));
        put(t("/modules/POULTRY"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        long plain = batch("Received birds");
        post(t("/livestock/flocks/" + plain + "/movements"), owner, Map.of("date", "2026-07-03", "kind", "DIED", "heads", 1)).andExpect(status().isCreated());
        long bought = batch("Bought birds");
        post(t("/livestock/flocks/" + bought + "/purchases"), owner, Map.of("date", "2026-07-02", "heads", 5,
                "bill", Map.of("amount", 600, "financialAccountId", cash))).andExpect(status().isCreated());

        delete(t("/livestock/groups/" + plain), owner).andExpect(status().isNoContent());
        delete(t("/livestock/groups/" + bought), owner).andExpect(status().isNoContent());
        JsonNode flocks = body(get(t("/livestock/flocks"), owner));
        assertThat(StreamSupport.stream(flocks.spliterator(), false).map(n -> n.get("id").asLong()).toList()).doesNotContain(plain, bought);
        assertThat(binNames()).contains("ANIMAL_GROUP:Received birds", "ANIMAL_GROUP:Bought birds");

        delete(t("/bin/ANIMAL_GROUP/" + bought), owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BIN_KEPT"));
        delete(t("/bin/ANIMAL_GROUP/" + plain), owner).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from sf_animal_groups where id = ?", Long.class, plain)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_head_movements where group_id = ?", Long.class, plain)).isZero();

        post(t("/bin/ANIMAL_GROUP/" + bought + "/restore"), owner, Map.of()).andExpect(status().isNoContent());
        assertThat(body(get(t("/livestock/flocks"), owner)).findValues("id").stream().map(JsonNode::asLong).toList()).contains(bought);
    }

    @Test
    void aCropCycleAndAWorkNoteGoToTheBin() throws Exception {
        newFinanceTenant("bin-crop-" + UUID.randomUUID().toString().substring(0, 6));
        long farm = newFarm(owner, tenant, "Main farm");
        long field = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "North", "area", 2))).get("id").asLong();
        long crop = body(post(t("/crops"), owner, Map.of("name", "Paddy"))).get("id").asLong();
        long cycle = body(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", field, "cropId", crop, "name", "Samba",
                "plannedSowingDate", "2026-06-01"))).get("id").asLong();
        long note = body(post(t("/activities"), owner, Map.of("cycleId", cycle, "activityType", "WEEDING", "activityDate", "2026-06-10", "inputs", List.of()))
                .andExpect(status().isCreated())).at("/activity/id").asLong();

        delete(t("/activities/" + note), owner).andExpect(status().isNoContent());
        assertThat(body(get(t("/activities?cycleId=" + cycle), owner)).get("items")).isEmpty();
        post(t("/bin/CROP_ACTIVITY/" + note + "/restore"), owner, Map.of()).andExpect(status().isNoContent());
        assertThat(body(get(t("/activities?cycleId=" + cycle), owner)).get("items")).hasSize(1);

        delete(t("/cultivation-cycles/" + cycle), owner).andExpect(status().isNoContent());
        assertThat(body(get(t("/cultivation-cycles"), owner)).get("items")).isEmpty();
        assertThat(jdbc.queryForObject("select active from sf_cost_objects where crop_cycle_id = ?", Boolean.class, cycle)).isFalse();
        delete(t("/bin/CROP_CYCLE/" + cycle), owner).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from sf_cultivation_cycles where id = ?", Long.class, cycle)).isZero();
    }

    @Test
    void onlyTheOwnerDeletesForeverAndAnotherTenantsItemsAreOutOfReach() throws Exception {
        newFinanceTenant("bin-b-" + UUID.randomUUID().toString().substring(0, 6));
        long tenantB = tenant;
        long farmB = newFarm(owner, tenantB, "B farm");
        newFinanceTenant("bin-a-" + UUID.randomUUID().toString().substring(0, 6));
        long farmA = newFarm(owner, tenant, "A farm");

        post(t("/bin/FARM/" + farmB), owner, Map.of()).andExpect(status().isNotFound());
        post(t("/bin/FARM/" + farmB + "/restore"), owner, Map.of()).andExpect(status().isNotFound());
        delete(t("/bin/FARM/" + farmB), owner).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select deleted_at is null from sf_farms where id = ?", Boolean.class, farmB)).isTrue();

        User manager = newUser("bin-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        post(t("/bin/FARM/" + farmA), manager, Map.of()).andExpect(status().isNoContent());
        delete(t("/bin/FARM/" + farmA), manager).andExpect(status().isForbidden());
        post(t("/bin/NOT_A_KIND/1"), owner, Map.of()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BIN_KIND_NOT_FOUND"));
    }
}
