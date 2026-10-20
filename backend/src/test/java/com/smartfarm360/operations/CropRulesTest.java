package com.smartfarm360.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.inventory.StockTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Crop management design, sections 3–8: stages, area, field records, harvests, allocation, observations, weather, reports. */
class CropRulesTest extends StockTestSupport {
    private long farm, north, paddy, samba, urea;

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("crop-" + UUID.randomUUID().toString().substring(0, 6));
        farm = newFarm(owner, tenant, "Main farm");
        north = parcel(farm, "North field", 2);
        paddy = id(post(t("/crops"), owner, Map.of("name", "Paddy")).andExpect(status().isCreated()));
        samba = cycle(north, paddy, "Samba 2026", "2026-07-01", 2);
        urea = item("Urea", "INPUT", "kg", Map.of());
        stockInvoice("bills", "2026-06-01", null, itemLine(urea, 100, null, 6));
    }

    private long parcel(long farmId, String name, int acres) throws Exception {
        return body(post(t("/farms/" + farmId + "/land-parcels"), owner, Map.of("name", name, "area", acres))).get("id").asLong();
    }

    private long cycle(long parcel, long crop, String name, String sowing, Object acres) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop, "name", name, "plannedSowingDate", sowing));
        if (acres != null) body.put("cultivatedArea", acres);
        return id(post(t("/cultivation-cycles"), owner, body).andExpect(status().isCreated()));
    }

    private JsonNode cycleNode(long id) throws Exception { return body(get(t("/cultivation-cycles/" + id), owner).andExpect(status().isOk())); }

    private JsonNode activity(Map<String, Object> body) throws Exception {
        return body(post(t("/activities"), owner, body).andExpect(status().isCreated()));
    }

    private Map<String, Object> notes(long cycle, String type, String date) {
        return new HashMap<>(Map.of("cycleId", cycle, "activityType", type, "activityDate", date, "notes", "note"));
    }

    private JsonNode harvest(long cycle, Object quantity, String unit, String date) throws Exception {
        return body(post(t("/harvests"), owner, Map.of("cycleId", cycle, "harvestDate", date, "quantity", quantity, "unit", unit))
                .andExpect(status().isCreated()));
    }

    @Test
    void stagesFollowFieldWorkAndClosedCyclesTakeNoNewFieldRecords() throws Exception {
        assertThat(cycleNode(samba).get("status").asText()).isEqualTo("PLANNED");
        activity(notes(samba, "TRANSPLANTING", "2026-07-03"));
        assertThat(cycleNode(samba).get("status").asText()).isEqualTo("GROWING");
        assertThat(cycleNode(samba).get("actualSowingDate").asText()).isEqualTo("2026-07-03");
        harvest(samba, 10, "quintal", "2026-10-01");
        assertThat(cycleNode(samba).get("status").asText()).isEqualTo("HARVESTING");
        post(t("/cultivation-cycles/" + samba + "/status"), owner, Map.of("action", "CLOSE", "date", "2026-10-05")).andExpect(status().isOk());
        assertThat(cycleNode(samba).get("closedOn").asText()).isEqualTo("2026-10-05");
        post(t("/activities"), owner, notes(samba, "WEEDING", "2026-10-06")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLE_CLOSED"));
        // Money can still be assigned to a closed cycle (late harvest labour bill)
        long co = cycleNode(samba).get("costObjectId").asLong();
        invoice("bills", "2026-10-10", null, cash, line("LABOUR", 500, co));
        // Only owners and farm administrators reopen
        User manager = newUser("crop-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        post(t("/cultivation-cycles/" + samba + "/status"), manager, Map.of("action", "REOPEN")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_OR_ADMIN_REQUIRED"));
        post(t("/cultivation-cycles/" + samba + "/status"), owner, Map.of("action", "REOPEN")).andExpect(status().isOk());
        assertThat(cycleNode(samba).get("status").asText()).isEqualTo("HARVESTING");
        // Cancelling is only for cycles without records
        post(t("/cultivation-cycles/" + samba + "/status"), owner, Map.of("action", "CANCEL")).andExpect(status().isConflict());
        long empty = cycle(parcel(farm, "Spare", 1), paddy, "Spare plan", "2026-08-01", null);
        post(t("/cultivation-cycles/" + empty + "/status"), owner, Map.of("action", "CANCEL")).andExpect(status().isOk());
        assertThat(cycleNode(empty).get("status").asText()).isEqualTo("CANCELLED");
        post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", north, "cropId", paddy, "name", "x",
                "plannedSowingDate", "2026-07-01", "status", "COMPLETED")).andExpect(status().isBadRequest());
    }

    @Test
    void cyclesCannotUseMoreLandThanTheFieldButIntercroppingIsWarnedNotBlocked() throws Exception {
        post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", north, "cropId", paddy, "name", "Too big",
                "plannedSowingDate", "2026-07-01", "cultivatedArea", 3, "areaUnit", "ACRE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AREA_EXCEEDS_FIELD"));
        // Black gram on 50 cents of the same 2-acre field while Samba uses all of it: allowed, with a warning (F11)
        long gram = id(post(t("/crops"), owner, Map.of("name", "Black gram")).andExpect(status().isCreated()));
        JsonNode inter = body(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", north, "cropId", gram, "name", "Gram",
                "plannedSowingDate", "2026-07-05", "cultivatedArea", 50, "areaUnit", "CENT")).andExpect(status().isCreated()));
        assertThat(inter.get("warnings").get(0).asText()).isEqualTo("FIELD_OVERCOMMITTED");
        money(inter.get("areaAcres"), "0.5");
        // Editing the area follows the same rule
        patch(t("/cultivation-cycles/" + samba), owner, Map.of("cultivatedArea", 1.5)).andExpect(status().isOk());
        assertThat(body(get(t("/cultivation-cycles/" + inter.get("id").asLong()), owner)).get("warnings")).isEmpty();
    }

    @Test
    void notesOnlyActivitiesChangeInPlaceAndRecordsWithStockOrMoneyAreReversed() throws Exception {
        long plain = activity(notes(samba, "WEEDING", "2026-07-10")).at("/activity/id").asLong();
        patch(t("/activities/" + plain), owner, Map.of("notes", "Two rounds", "workers", 4)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Two rounds")).andExpect(jsonPath("$.workers").value(4));
        delete(t("/activities/" + plain), owner).andExpect(status().isNoContent());

        Map<String, Object> body = notes(samba, "FERTILIZER", "2026-07-12");
        body.put("inputs", List.of(Map.of("itemId", urea, "quantity", 30)));
        body.put("paidWork", Map.of("categoryId", categoryId("LABOUR"), "amount", 500, "financialAccountId", cash));
        JsonNode rec = activity(body);
        long id = rec.at("/activity/id").asLong();
        money(stockItem(urea).get("quantity"), "70");
        money(accountBalance("5000"), "180");
        money(accountBalance("5100"), "500");
        patch(t("/activities/" + id), owner, Map.of("notes", "x")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVITY_HAS_RECORDS"));
        delete(t("/activities/" + id), owner).andExpect(status().isConflict());

        post(t("/activities/" + id + "/reverse"), owner, Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));
        money(stockItem(urea).get("quantity"), "100");
        money(accountBalance("5000"), "0");
        money(accountBalance("5100"), "0");
        post(t("/activities/" + id + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));
    }

    @Test
    void stockInputsNeedStockPermissionAndRetriesRecordOnce() throws Exception {
        User supervisor = newUser("crop-supervisor");
        addMember(owner, tenant, supervisor, "FIELD_SUPERVISOR");
        Map<String, Object> body = notes(samba, "FERTILIZER", "2026-07-12");
        body.put("inputs", List.of(Map.of("itemId", urea, "quantity", 5)));
        post(t("/activities"), supervisor, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STOCK_PERMISSION_REQUIRED"));
        post(t("/activities"), supervisor, notes(samba, "WEEDING", "2026-07-12")).andExpect(status().isCreated());
        // Workers record harvests (farm produce at nil value) without stock permission
        User worker = newUser("crop-worker");
        addMember(owner, tenant, worker, "WORKER");
        post(t("/harvests"), worker, Map.of("cycleId", samba, "harvestDate", "2026-10-01", "quantity", 5, "unit", "quintal")).andExpect(status().isCreated());

        for (int i = 0; i < 2; i++) {
            mvc.perform(MockMvcRequestBuilders.post(t("/activities")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "act-1")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isCreated());
        }
        money(stockItem(urea).get("quantity"), "95");
        assertThat(jdbc.queryForObject("select count(*) from sf_farm_activities where tenant_id = ? and idempotency_key = 'act-1'", Long.class, tenant)).isEqualTo(1);
        body.put("notes", "different");
        mvc.perform(MockMvcRequestBuilders.post(t("/activities")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "act-1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void harvestsBecomeLotsAndCanBeReversedOnlyWhileTheProduceIsUnsold() throws Exception {
        JsonNode h1 = harvest(samba, 10, "quintal", "2026-10-01");
        JsonNode h2 = harvest(samba, 500, "kg", "2026-10-02");
        assertThat(h1.at("/harvest/lotNo").asText()).isEqualTo("SAMBA-2026-H1");
        assertThat(h2.at("/harvest/lotNo").asText()).isEqualTo("SAMBA-2026-H2");
        long item = h1.at("/harvest/itemId").asLong();
        JsonNode produce = stockItem(item);
        assertThat(produce.get("kind").asText()).isEqualTo("PRODUCE");
        assertThat(produce.get("trackLots").asBoolean()).isTrue();
        money(produce.get("quantity"), "1500");
        money(produce.get("value"), "0");
        // Sell part of the first lot; that harvest can no longer be reversed, the second can
        Map<String, Object> line = new HashMap<>(itemLine(item, 100, null, 20));
        line.put("lotNo", "SAMBA-2026-H1");
        stockInvoice("sales", "2026-10-03", null, line);
        post(t("/harvests/" + h1.at("/harvest/id").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_ALREADY_USED"));
        post(t("/harvests/" + h2.at("/harvest/id").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        money(stockItem(item).get("quantity"), "900");
        JsonNode r = body(get(t("/cultivation-cycles/" + samba + "/report?from=2026-07-01&to=2026-10-31"), owner));
        money(r.at("/harvested/0/quantity"), "1000");
        money(r.get("unsold"), "900");
        money(r.get("revenueTotal"), "2000");
        // The crop's produce item can be changed only to farm produce
        put(t("/crops/" + paddy + "/produce-item"), owner, Map.of("itemId", urea)).andExpect(status().isBadRequest());
        get(t("/crops/" + paddy + "/produce-item"), owner).andExpect(jsonPath("$.item.id").value(item));
    }

    @Test
    void overheadAllocationIsOnePerPeriodFarmAwareAndRoundsExactly() throws Exception {
        long south = parcel(farm, "South field", 2);
        long east = parcel(farm, "East field", 2);
        long otherFarm = newFarm(owner, tenant, "Hill farm");
        long hillField = body(post(t("/farms/" + otherFarm + "/land-parcels"), owner, Map.of("name", "Hill field", "area", 2))).get("id").asLong();
        long c2 = cycle(south, paddy, "Kuruvai", "2026-07-01", 2);
        long c3 = cycle(east, paddy, "Navarai", "2026-07-01", 2);
        Map<String, Object> hill = Map.of("farmId", otherFarm, "landParcelId", hillField, "cropId", paddy, "name", "Hill paddy", "plannedSowingDate", "2026-07-01");
        long c4 = id(post(t("/cultivation-cycles"), owner, hill).andExpect(status().isCreated()));

        post(f("/overhead-allocations"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_ALLOCATE"));
        // Rs 100 for the main farm only, Rs 40 for the whole business
        Map<String, Object> farmBill = new HashMap<>(Map.of("docDate", "2026-07-10", "financialAccountId", cash, "farmId", farm,
                "lines", List.of(line("REPAIRS", 100, null))));
        post(f("/bills"), owner, farmBill).andExpect(status().isCreated());
        invoice("bills", "2026-07-11", null, cash, line("OTHER_EXPENSE", 40, null));
        JsonNode p = body(post(f("/overhead-allocations/preview"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isOk()));
        JsonNode repairs = StreamSupport.find(p.get("groups"), "categoryCode", "REPAIRS");
        assertThat(repairs.get("shares")).hasSize(3);   // only the main farm's three cycles
        assertThat(List.of(repairs.at("/shares/0/amount").asText(), repairs.at("/shares/1/amount").asText(), repairs.at("/shares/2/amount").asText()))
                .containsExactly("33.33", "33.33", "33.34");
        assertThat(StreamSupport.find(p.get("groups"), "categoryCode", "OTHER_EXPENSE").get("shares")).hasSize(4);
        long alloc = id(post(f("/overhead-allocations"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isCreated()));
        post(f("/overhead-allocations"), owner, Map.of("from", "2026-07-15", "to", "2026-08-15")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXISTS"));
        money(report("income-statement?from=2026-07-01&to=2026-07-31").get("operatingCostTotal"), "140.00");
        // Reverse and run again
        post(f("/documents/" + alloc + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        post(f("/overhead-allocations"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isCreated());
        JsonNode hillReport = body(get(t("/cultivation-cycles/" + c4 + "/report?from=2026-07-01&to=2026-07-31"), owner));
        money(hillReport.get("allocatedTotal"), "10.00");
        assertThat(c2 + c3).isPositive();
        // Workers cannot allocate
        User worker = newUser("alloc-worker");
        addMember(owner, tenant, worker, "WORKER");
        post(f("/overhead-allocations/preview"), worker, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isForbidden());
    }

    @Test
    void observationsAndWeatherNotes() throws Exception {
        long act = activity(notes(samba, "PESTICIDE", "2026-07-20")).at("/activity/id").asLong();
        JsonNode o = body(post(t("/cultivation-cycles/" + samba + "/observations"), owner, Map.of("observedOn", "2026-07-18", "kind", "PEST",
                "severity", "HIGH", "fieldPart", "North corner", "notes", "Stem borer", "activityId", act)).andExpect(status().isCreated()));
        long other = cycle(parcel(farm, "Other", 1), paddy, "Other", "2026-07-01", null);
        long otherAct = activity(notes(other, "WEEDING", "2026-07-20")).at("/activity/id").asLong();
        put(t("/observations/" + o.get("id").asLong()), owner, Map.of("observedOn", "2026-07-18", "kind", "PEST", "severity", "MEDIUM",
                "activityId", otherAct)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_IN_CYCLE"));
        put(t("/observations/" + o.get("id").asLong()), owner, Map.of("observedOn", "2026-07-18", "kind", "PEST", "severity", "MEDIUM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.severity").value("MEDIUM"));
        assertThat(body(get(t("/cultivation-cycles/" + samba + "/observations"), owner))).hasSize(1);
        delete(t("/observations/" + o.get("id").asLong()), owner).andExpect(status().isNoContent());

        put(t("/farms/" + farm + "/weather/2026-07-01"), owner, Map.of("rainfallMm", 12.5)).andExpect(status().isOk());
        put(t("/farms/" + farm + "/weather/2026-07-02"), owner, Map.of("rainfallMm", 3, "notes", "Drizzle")).andExpect(status().isOk());
        put(t("/farms/" + farm + "/weather/2026-07-01"), owner, Map.of("rainfallMm", 10)).andExpect(status().isOk());
        JsonNode w = body(get(t("/farms/" + farm + "/weather?from=2026-07-01&to=2026-07-31"), owner));
        money(w.get("totalRainfallMm"), "13");
        put(t("/farms/" + farm + "/weather/" + LocalDate.now().plusDays(3)), owner, Map.of("rainfallMm", 1)).andExpect(status().isBadRequest());
        put(t("/farms/" + farm + "/weather/2026-07-03"), owner, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEATHER_EMPTY"));
    }

    @Test
    void comparisonListsEveryCycleOfACropAndExportsCsv() throws Exception {
        long south = parcel(farm, "South field", 2);
        long kuruvai = cycle(south, paddy, "Kuruvai 2026", "2026-06-01", 2);
        harvest(kuruvai, 30, "quintal", "2026-09-01");
        harvest(samba, 40, "quintal", "2026-10-01");
        JsonNode cmp = body(get(t("/crops/" + paddy + "/comparison"), owner).andExpect(status().isOk()));
        assertThat(cmp.get("cycles")).hasSize(2);
        money(StreamSupport.find(cmp.get("cycles"), "cycleName", "Samba 2026").get("yieldPerAcre"), "2000");
        String csv = get(t("/crops/" + paddy + "/comparison.csv"), owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Kuruvai 2026").contains("Samba 2026");
        String report = get(t("/cultivation-cycles/" + samba + "/report.csv?price=20"), owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(report).contains("estimate (not in accounts)");
        JsonNode page = body(get(t("/cultivation-cycles?size=1&page=0"), owner));
        assertThat(page.get("totalItems").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(1);
    }

    /** Finds an element of a JSON array by a text field. */
    static final class StreamSupport {
        static JsonNode find(JsonNode array, String field, String value) {
            for (JsonNode n : array) if (value.equals(n.path(field).asText())) return n;
            throw new AssertionError("No element with " + field + "=" + value);
        }
    }
}
