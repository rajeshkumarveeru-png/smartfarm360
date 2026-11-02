package com.smartfarm360.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The farm and crop-cycle workspaces: the records of a farm (by the farm on the header or a line) and of an activity (by a
 * line booked to it), and their money.
 */
class FarmRecordsTest extends FinanceTestSupport {

    private long farm(String name) throws Exception {
        return id(post("/api/v1/tenants/" + tenant + "/farms", owner, Map.of("name", name, "farmType", "CROP")).andExpect(status().isCreated()));
    }

    private long bill(String date, Long headerFarm, Map<String, Object> line) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("docDate", date, "financialAccountId", cash, "lines", List.of(line)));
        if (headerFarm != null) body.put("farmId", headerFarm);
        return body(post(f("/bills"), owner, body).andExpect(status().isCreated())).at("/summary/id").asLong();
    }

    private List<Long> recordIds(String query) throws Exception {
        JsonNode items = body(get(f("/documents?" + query), owner).andExpect(status().isOk())).get("items");
        return StreamSupport.stream(items.spliterator(), false).map(n -> n.get("id").asLong()).toList();
    }

    @Test
    void recordsAndMoneyAreFilteredByFarm() throws Exception {
        newFinanceTenant("farm-records");
        long east = farm("East field"), west = farm("West field");
        long eastBill = bill("2026-07-02", east, line("SEEDS", 100, null));
        Map<String, Object> westLine = line("FERTILIZER", 40, null);
        westLine.put("farmId", west);
        long westBill = bill("2026-07-03", null, westLine);
        long anyFarm = bill("2026-07-04", null, line("SEEDS", 7, null));

        assertThat(recordIds("farmId=" + east)).containsExactly(eastBill);
        assertThat(recordIds("farmId=" + west)).containsExactly(westBill);
        assertThat(recordIds("type=BILL")).contains(eastBill, westBill, anyFarm);

        money(report("income-statement?from=2026-07-01&to=2026-07-31&farmId=" + east).get("operatingCostTotal"), "100");
        money(report("income-statement?from=2026-07-01&to=2026-07-31&farmId=" + west).get("operatingCostTotal"), "40");
    }

    @Test
    void recordsAreFilteredByActivity() throws Exception {
        newFinanceTenant("activity-records");
        long activity = body(get(f("/cost-objects"), owner)).get(0).get("id").asLong();
        long booked = bill("2026-07-02", null, line("SEEDS", 90, activity));
        long other = bill("2026-07-03", null, line("SEEDS", 10, null));
        assertThat(recordIds("costObjectId=" + activity)).containsExactly(booked).doesNotContain(other);
    }

    @Test
    void anotherTenantsActivityCannotBeUsedToListRecords() throws Exception {
        newFinanceTenant("activity-records-b");
        long activityB = body(get(f("/cost-objects"), owner)).get(0).get("id").asLong();
        newFinanceTenant("activity-records-a");
        get(f("/documents?costObjectId=" + activityB), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COST_OBJECT_NOT_FOUND"));
    }

    @Test
    void anotherTenantsFarmCannotBeUsedToListRecords() throws Exception {
        newFinanceTenant("farm-records-b");
        long farmB = farm("B field");
        newFinanceTenant("farm-records-a");
        get(f("/documents?farmId=" + farmB), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FARM_NOT_FOUND"));
    }
}
