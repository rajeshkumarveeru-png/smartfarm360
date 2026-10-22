package com.smartfarm360.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.inventory.StockTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mobile offline design (Phase 13): the idempotency store (N4), day-record versions (N7), the client version check (N14). */
class OfflineSyncTest extends StockTestSupport {
    private long farm;
    private long cycle;

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("off-" + UUID.randomUUID().toString().substring(0, 6));
        farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long paddy = id(post(t("/crops"), owner, Map.of("name", "Paddy")).andExpect(status().isCreated()));
        cycle = id(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", paddy, "name", "Samba 2026",
                "plannedSowingDate", "2026-07-01", "cultivatedArea", 2)).andExpect(status().isCreated()));
    }

    private ResultActions send(String method, String url, User user, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) b.header(IdempotencyFilter.HEADER, key);
        return mvc.perform(b);
    }

    private Map<String, Object> observation(String notes) {
        return Map.of("observedOn", "2026-07-10", "kind", "PEST", "severity", "LOW", "notes", notes);
    }

    private long worker(String name) throws Exception {
        return body(post(t("/workforce/workers"), owner, Map.of("name", name, "payType", "DAILY", "rate", 450, "joinedOn", "2026-07-01"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long observations() {
        return jdbc.queryForObject("select count(*) from sf_crop_observations where tenant_id = ?", Long.class, tenant);
    }

    // ---- Idempotency store (N4) ----------------------------------------------------------------------------------------

    @Test
    void aRetryWithTheSameKeyAndBodyGetsTheStoredReplyAndRecordsOnce() throws Exception {
        String url = t("/cultivation-cycles/" + cycle + "/observations");
        JsonNode first = body(send("POST", url, owner, "01J9ZK3E-obs-1", observation("Stem borer")).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
        JsonNode again = body(send("POST", url, owner, "01J9ZK3E-obs-1", observation("Stem borer")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(observations()).isEqualTo(1);
        // Without a key nothing changes for the web app: every save records
        send("POST", url, owner, null, observation("Stem borer")).andExpect(status().isCreated());
        assertThat(observations()).isEqualTo(2);
    }

    @Test
    void aKeyIsRefusedForADifferentBodyOrUserAndWhileTheFirstRequestRuns() throws Exception {
        String url = t("/cultivation-cycles/" + cycle + "/observations");
        send("POST", url, owner, "key-reused-1", observation("Stem borer")).andExpect(status().isCreated());
        send("POST", url, owner, "key-reused-1", observation("Leaf folder")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        User manager = newUser("off-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        send("POST", url, manager, "key-reused-1", observation("Stem borer")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(observations()).isEqualTo(1);
        // A request that has claimed the key but not finished
        jdbc.update("insert into sf_idempotent_requests (tenant_id, user_id, idempotency_key, method, path, request_hash) values (?, ?, 'key-running-1', 'POST', ?, ?)",
                tenant, owner.id(), url, IdempotencyInterceptor.hash("POST", url, json.writeValueAsBytes(observation("Stem borer"))));
        send("POST", url, owner, "key-running-1", observation("Stem borer")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        send("POST", url, owner, "bad key!", observation("Stem borer")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        assertThat(observations()).isEqualTo(1);
    }

    @Test
    void aRefusedRequestReleasesItsKeySoTheCorrectedRecordCanBeSent() throws Exception {
        long w1 = worker("Kumar");
        String future = java.time.LocalDate.now().plusDays(2).toString();
        send("PUT", t("/workforce/attendance/" + future), owner, "att-release-1", Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_DATE"));
        assertThat(jdbc.queryForObject("select count(*) from sf_idempotent_requests where tenant_id = ?", Long.class, tenant)).isZero();
        send("PUT", t("/workforce/attendance/2026-07-02"), owner, "att-release-1", Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from sf_idempotent_requests where tenant_id = ? and response_status = 200", Long.class, tenant)).isEqualTo(1);
    }

    @Test
    void keysBelongToOneFarmBusinessAndAreNeverReplayedToAnother() throws Exception {
        String url = t("/cultivation-cycles/" + cycle + "/observations");
        send("POST", url, owner, "shared-key-1", observation("Stem borer")).andExpect(status().isCreated());
        // Another business's owner cannot use this business, with or without the key
        User other = newUser("off-other");
        long otherTenant = newTenant(other, "Other farm");
        send("POST", url, other, "shared-key-1", observation("Stem borer")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        // The same key in their own business is a new record there
        long otherFarm = newFarm(other, otherTenant, "Their farm");
        String o = "/api/v1/tenants/" + otherTenant;
        long parcel = body(post(o + "/farms/" + otherFarm + "/land-parcels", other, Map.of("name", "F", "area", 1))).get("id").asLong();
        long crop = id(post(o + "/crops", other, Map.of("name", "Paddy")).andExpect(status().isCreated()));
        long theirCycle = id(post(o + "/cultivation-cycles", other, Map.of("farmId", otherFarm, "landParcelId", parcel, "cropId", crop, "name", "Kuruvai",
                "plannedSowingDate", "2026-07-01")).andExpect(status().isCreated()));
        send("POST", o + "/cultivation-cycles/" + theirCycle + "/observations", other, "shared-key-1", observation("Stem borer"))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(jdbc.queryForList("select tenant_id from sf_idempotent_requests where idempotency_key = 'shared-key-1' order by tenant_id", Long.class))
                .containsExactlyInAnyOrder(tenant, otherTenant);
        assertThat(observations()).isEqualTo(1);
    }

    // ---- Day-record versions (N7) --------------------------------------------------------------------------------------

    @Test
    void attendanceSavedFromAnOlderVersionIsRefused() throws Exception {
        long w1 = worker("Kumar");
        String url = t("/workforce/attendance/2026-07-02");
        String v0 = body(get(url, owner).andExpect(status().isOk())).get("version").asText();
        assertThat(v0).isEqualTo(Versions.NONE);
        String v1 = body(put(url, owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT")), "baseVersion", v0))
                .andExpect(status().isOk())).get("version").asText();
        assertThat(v1).isNotEqualTo(v0);
        put(url, owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "ABSENT")), "baseVersion", v0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"));
        assertThat(body(get(url, owner)).get("version").asText()).isEqualTo(v1);
        put(url, owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "HALF_DAY")), "baseVersion", v1)).andExpect(status().isOk());
        // Without a base version (the web before this phase, scripts) the save is not checked
        put(url, owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT")))).andExpect(status().isOk());
    }

    @Test
    void milkSessionsPoultryDaysAndPondDaysSavedFromAnOlderVersionAreRefused() throws Exception {
        put(t("/modules/POULTRY"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        put(t("/modules/AQUACULTURE"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        // Milk: each group's session has its own version
        long herd = body(post(t("/livestock/groups"), owner, Map.of("name", "Milking herd", "purpose", "MILKING")).andExpect(status().isCreated())).get("id").asLong();
        String milk = t("/livestock/milk/2026-10-20");
        JsonNode m1 = body(put(milk, owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 42, "baseVersion", "0"))))
                .andExpect(status().isOk()));
        String mv = m1.at("/sessions/0/version").asText();
        put(milk, owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 40, "baseVersion", "0"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"));
        put(milk, owner, Map.of("sessions", List.of(Map.of("session", "EVENING", "groupId", herd, "litres", 30, "baseVersion", "0")))).andExpect(status().isOk());
        put(milk, owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 40, "baseVersion", mv)))).andExpect(status().isOk());
        assertThat(body(get(milk, owner)).get("total").decimalValue()).isEqualByComparingTo("70");

        // Poultry daily record
        long layers = body(post(t("/livestock/flocks"), owner, Map.of("name", "Layer shed A", "species", "CHICKEN", "purpose", "LAYER", "placedOn", "2026-06-01",
                "heads", 100)).andExpect(status().isCreated())).get("id").asLong();
        String day = t("/livestock/flocks/" + layers + "/days/2026-07-01");
        assertThat(body(get(day, owner)).get("version").asText()).isEqualTo(Versions.NONE);
        String dv = body(put(day, owner, Map.of("deaths", 2, "eggsGood", 80, "baseVersion", "0")).andExpect(status().isOk())).get("version").asText();
        put(day, owner, Map.of("deaths", 1, "eggsGood", 80, "baseVersion", "0")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"));
        put(day, owner, Map.of("deaths", 1, "eggsGood", 80, "baseVersion", dv)).andExpect(status().isOk()).andExpect(jsonPath("$.deaths").value(1));

        // Pond daily record
        long pond = body(post(t("/aquaculture/ponds"), owner, Map.of("name", "Pond 1", "farmId", farm, "kind", "EARTHEN", "waterArea", 50, "areaUnit", "CENT"))
                .andExpect(status().isCreated())).get("id").asLong();
        long pc = body(post(t("/aquaculture/ponds/" + pond + "/cycles"), owner, Map.of("startedOn", "2026-05-20")).andExpect(status().isCreated())).get("id").asLong();
        String pd = t("/aquaculture/cycles/" + pc + "/days/2026-06-05");
        String pv = body(put(pd, owner, Map.of("notes", "Water green", "baseVersion", "0")).andExpect(status().isOk())).get("version").asText();
        put(pd, owner, Map.of("notes", "Water clear", "baseVersion", "0")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"));
        put(pd, owner, Map.of("notes", "Water clear", "baseVersion", pv)).andExpect(status().isOk()).andExpect(jsonPath("$.notes").value("Water clear"));
    }

    // ---- Client version (N14) ------------------------------------------------------------------------------------------

    @Test
    void phoneAppsOlderThanTheMinimumAreAskedToUpdate() throws Exception {
        assertThat(ClientVersionFilter.compare("1.10.0", "1.9")).isPositive();
        assertThat(ClientVersionFilter.compare("1.2", "1.2.0")).isZero();
        assertThat(ClientVersionFilter.compare("0.9.9", "1.0")).isNegative();
        ClientVersionFilter filter = new ClientVersionFilter("1.2.0");
        MockHttpServletResponse old = run(filter, "1.1.9");
        assertThat(old.getStatus()).isEqualTo(426);
        assertThat(old.getContentAsString()).contains("CLIENT_UPGRADE_REQUIRED");
        assertThat(run(filter, "1.2.0").getStatus()).isEqualTo(200);
        assertThat(run(filter, null).getStatus()).isEqualTo(200);                        // the web app sends no version
        assertThat(run(new ClientVersionFilter(""), "0.1").getStatus()).isEqualTo(200);   // no minimum configured
        // The running app has no minimum configured: a phone that sends its version is served as before
        mvc.perform(MockMvcRequestBuilders.get(t("/farms")).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                .header(ClientVersionFilter.HEADER, "0.1.0")).andExpect(status().isOk());
    }

    private static MockHttpServletResponse run(ClientVersionFilter filter, String version) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/1/farms");
        if (version != null) request.addHeader(ClientVersionFilter.HEADER, version);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    // ---- Worked example (design section 10) ------------------------------------------------------------------------------

    @Test
    void murugansMorningOfflineIsSentInOrderWithTheMilkAndTheUreaNeedingAttention() throws Exception {
        put(t("/modules/POULTRY"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        put(t("/modules/AQUACULTURE"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        // Murugan records fertiliser from stock and a diesel expense, so he is a farm manager here (a field supervisor
        // has neither stock nor finance permissions)
        User murugan = newUser("murugan");
        addMember(owner, tenant, murugan, "FARM_MANAGER");
        List<Map<String, Object>> crew = new ArrayList<>();
        for (int i = 1; i <= 6; i++) crew.add(Map.of("workerId", worker("Worker " + i), "status", "PRESENT"));
        long herd = body(post(t("/livestock/groups"), owner, Map.of("name", "Milking herd", "purpose", "MILKING")).andExpect(status().isCreated())).get("id").asLong();
        long layers = body(post(t("/livestock/flocks"), owner, Map.of("name", "Layer shed A", "species", "CHICKEN", "purpose", "LAYER", "placedOn", "2026-06-01",
                "heads", 1000)).andExpect(status().isCreated())).get("id").asLong();
        long feed = item("Layer mash", "INPUT", "kg", Map.of("expenseCategoryId", categoryId("ANIMAL_FEED")));
        long urea = item("Urea", "INPUT", "kg", Map.of());
        stockInvoice("bills", "2026-10-01", null, itemLine(feed, 500, null, 30), itemLine(urea, 40, null, 6));
        long pond = body(post(t("/aquaculture/ponds"), owner, Map.of("name", "Pond 1", "farmId", farm, "kind", "EARTHEN", "waterArea", 50, "areaUnit", "CENT"))
                .andExpect(status().isCreated())).get("id").asLong();
        put(t("/aquaculture/water-limits"), owner, Map.of("limits", List.of(Map.of("parameter", "DISSOLVED_OXYGEN", "lowest", 4)))).andExpect(status().isOk());
        String milkUrl = t("/livestock/milk/2026-10-20");
        put(milkUrl, owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 41)))).andExpect(status().isOk());

        // What the phone saw at its last sync (Sunday evening)
        String attendanceSeen = body(get(t("/workforce/attendance/2026-10-20"), murugan)).get("version").asText();
        String milkSeen = body(get(milkUrl, murugan)).at("/sessions/0/version").asText();
        String poultrySeen = body(get(t("/livestock/flocks/" + layers + "/days/2026-10-20"), murugan)).get("version").asText();

        // 08:15: Selvi corrects the morning milk on the web; the office uses 30 kg of the 40 kg of Urea
        put(milkUrl, owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 40, "baseVersion", milkSeen)))).andExpect(status().isOk());
        post(s("/issues"), owner, Map.of("docDate", "2026-10-20", "lines", List.of(Map.of("itemId", urea, "quantity", 30, "unit", "kg")))).andExpect(status().isCreated());

        // 11:00: the outbox, oldest first; each record carries its client id
        String at = "2026-10-20T08:00:00+05:30";
        Map<String, Object> attendance = Map.of("entries", crew, "baseVersion", attendanceSeen);
        ResultActions sent = send("PUT", t("/workforce/attendance/2026-10-20"), murugan, "0192a6f0-0001", attendance).andExpect(status().isOk());
        send("PUT", milkUrl, murugan, "0192a6f0-0002", Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", herd, "litres", 42, "baseVersion", milkSeen))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECORD"));
        send("PUT", t("/livestock/flocks/" + layers + "/days/2026-10-20"), murugan, "0192a6f0-0003",
                Map.of("deaths", 2, "eggsGood", 850, "feedItemId", feed, "feedKg", 110, "baseVersion", poultrySeen)).andExpect(status().isOk());
        Map<String, Object> activity = new HashMap<>(Map.of("cycleId", cycle, "activityType", "FERTILIZER", "activityDate", "2026-10-20",
                "inputs", List.of(Map.of("itemId", urea, "quantity", 50))));
        send("POST", t("/activities"), murugan, "0192a6f0-0004", activity).andExpect(status().isConflict());
        send("POST", f("/bills"), murugan, "0192a6f0-0005", Map.of("docDate", "2026-10-20", "financialAccountId", cash,
                "lines", List.of(line("OTHER_INPUTS", 600, null)))).andExpect(status().isCreated());
        send("POST", t("/aquaculture/ponds/" + pond + "/readings"), murugan, "0192a6f0-0006", Map.of("takenAt", at, "dissolvedOxygen", 3.2))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outOfLimits[0]").value("DISSOLVED_OXYGEN"));

        // The attendance reply was lost: the retry gets the stored reply, one attendance day, not two
        String reply = sent.andReturn().getResponse().getContentAsString();
        String retried = send("PUT", t("/workforce/attendance/2026-10-20"), murugan, "0192a6f0-0001", attendance).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(retried).isEqualTo(reply);
        assertThat(jdbc.queryForObject("select count(*) from sf_attendance where tenant_id = ? and work_date = '2026-10-20'", Long.class, tenant)).isEqualTo(6);

        // Murugan keeps the server's milk and resends the activity with the 10 kg left, under the same client id
        assertThat(body(get(milkUrl, murugan)).get("total").decimalValue()).isEqualByComparingTo("40");
        activity.put("inputs", List.of(Map.of("itemId", urea, "quantity", 10)));
        send("POST", t("/activities"), murugan, "0192a6f0-0004", activity).andExpect(status().isCreated());
        money(stockItem(urea).get("quantity"), "0");
        money(stockItem(feed).get("quantity"), "390");
        assertThat(journalImbalance()).isZero();
    }
}
