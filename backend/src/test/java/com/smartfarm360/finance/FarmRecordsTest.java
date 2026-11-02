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
    void recordsShowTheirCategoriesAndFilterByCategoryWithinAnActivity() throws Exception {
        newFinanceTenant("category-records");
        long activity = body(get(f("/cost-objects"), owner)).get(0).get("id").asLong();
        Map<String, Object> seedsHere = line("SEEDS", 300, activity);
        Map<String, Object> fuelElsewhere = line("FERTILIZER", 50, null);
        long mixed = body(post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash, "lines", List.of(seedsHere, fuelElsewhere)))
                .andExpect(status().isCreated())).at("/summary/id").asLong();
        long fertilizerHere = bill("2026-07-03", null, line("FERTILIZER", 70, activity));

        JsonNode items = body(get(f("/documents?costObjectId=" + activity), owner).andExpect(status().isOk())).get("items");
        JsonNode mixedRow = StreamSupport.stream(items.spliterator(), false).filter(n -> n.get("id").asLong() == mixed).findFirst().orElseThrow();
        // Only the line booked to the activity is listed for it
        assertThat(mixedRow.get("categories")).hasSize(1);
        assertThat(mixedRow.at("/categories/0/code").asText()).isEqualTo("SEEDS");
        money(mixedRow.at("/categories/0/amount"), "300");

        long seeds = mixedRow.at("/categories/0/categoryId").asLong();
        long fertilizer = categoryId("FERTILIZER");
        assertThat(recordIds("costObjectId=" + activity + "&categoryId=" + seeds)).containsExactly(mixed);
        assertThat(recordIds("costObjectId=" + activity + "&categoryId=" + fertilizer)).containsExactly(fertilizerHere);
        assertThat(recordIds("categoryId=" + fertilizer)).contains(mixed, fertilizerHere);
        // The report also counts lines booked to no activity under overhead (the first cost object), so 300 + 70 + 50
        money(report("categories?from=2026-07-01&to=2026-07-31&kind=COSTS&costObjectId=" + activity).get("total"), "420");
    }

    @Test
    void anotherTenantsCategoryCannotBeUsedToListRecords() throws Exception {
        newFinanceTenant("category-records-b");
        long categoryB = id(post(f("/categories"), owner, Map.of("name", "B special", "kind", "EXPENSE", "accountCode", "5600")));
        newFinanceTenant("category-records-a");
        get(f("/documents?categoryId=" + categoryB), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
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
