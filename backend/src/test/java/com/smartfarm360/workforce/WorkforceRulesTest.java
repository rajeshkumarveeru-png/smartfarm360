package com.smartfarm360.workforce;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Workforce design, sections 3–5: rates, attendance, pay runs, advances, payments, reversal, locking, permissions. */
class WorkforceRulesTest extends FinanceTestSupport {
    private long samba;

    private String w(String path) { return "/api/v1/tenants/" + tenant + "/workforce" + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("wf-" + UUID.randomUUID().toString().substring(0, 6));
        long farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post("/api/v1/tenants/" + tenant + "/farms/" + farm + "/land-parcels", owner, Map.of("name", "F", "area", 2))).get("id").asLong();
        long crop = body(post("/api/v1/tenants/" + tenant + "/crops", owner, Map.of("name", "Paddy"))).get("id").asLong();
        samba = body(post("/api/v1/tenants/" + tenant + "/cultivation-cycles", owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop,
                "name", "Samba", "plannedSowingDate", "2026-06-01"))).get("costObjectId").asLong();
    }

    private long worker(String name, String payType, Object rate, String joined, String pieceUnit) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "payType", payType, "rate", rate, "joinedOn", joined));
        if (pieceUnit != null) body.put("pieceUnit", pieceUnit);
        return body(post(w("/workers"), owner, body).andExpect(status().isCreated())).get("id").asLong();
    }

    private void day(long worker, String date, String status, Long costObject) throws Exception {
        Map<String, Object> e = new HashMap<>(Map.of("workerId", worker, "status", status));
        if (costObject != null) e.put("costObjectId", costObject);
        put(w("/attendance/" + date), owner, Map.of("entries", List.of(e))).andExpect(status().isOk());
    }

    private JsonNode payRun(Map<String, Object> body) throws Exception { return body(post(w("/pay-runs"), owner, body).andExpect(status().isCreated())); }

    private JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).filter(x -> value.equals(x.path(field).asText())).findFirst().orElseThrow();
    }

    @Test
    void dailyPayCountsHalfDaysAndPaidLeaveAtTheRateOfEachDay() throws Exception {
        long w1 = worker("Kumar", "DAILY", 450, "2026-07-01", null);
        post(w("/workers/" + w1 + "/rates"), owner, Map.of("effectiveFrom", "2026-07-03", "rate", 500)).andExpect(status().isOk());
        day(w1, "2026-07-01", "PRESENT", samba);       // 450 Samba
        day(w1, "2026-07-02", "HALF_DAY", samba);      // 225 Samba
        day(w1, "2026-07-03", "PAID_LEAVE", samba);    // 500 overhead (leave is not work on a crop)
        day(w1, "2026-07-04", "UNPAID_LEAVE", null);
        day(w1, "2026-07-05", "ABSENT", null);
        JsonNode p = body(post(w("/pay-runs/preview"), owner, Map.of("from", "2026-07-01", "to", "2026-07-07")).andExpect(status().isOk()));
        JsonNode k = find(p.get("workers"), "name", "Kumar");
        money(k.get("gross"), "1175.00");
        money(k.get("days"), "2.5");
        money(find(k.get("costs"), "costObjectId", String.valueOf(samba)).get("amount"), "675.00");
    }

    @Test
    void monthlyPayDeductsUnpaidLeaveAndSplitsCostByDaysWorked() throws Exception {
        long w1 = worker("Ravi", "MONTHLY", 15000, "2026-06-01", null);    // June has 30 days
        for (int d = 1; d <= 3; d++) day(w1, "2026-06-0" + d, "UNPAID_LEAVE", null);
        for (int d = 4; d <= 9; d++) day(w1, "2026-06-0" + d, "PRESENT", samba);
        JsonNode p = body(post(w("/pay-runs/preview"), owner, Map.of("from", "2026-06-01", "to", "2026-06-30")).andExpect(status().isOk()));
        JsonNode r = find(p.get("workers"), "name", "Ravi");
        money(r.get("gross"), "13500.00");                              // 15,000 x 27/30
        money(find(r.get("costs"), "costObjectId", String.valueOf(samba)).get("amount"), "3000.00");   // 13,500 x 6/27
        // A weekly run leaves monthly workers out, and refuses them when named
        JsonNode weekly = body(post(w("/pay-runs/preview"), owner, Map.of("from", "2026-06-01", "to", "2026-06-07")).andExpect(status().isOk()));
        assertThat(weekly.get("workers")).isEmpty();
        assertThat(weekly.get("skipped").get(0).asText()).startsWith("PARTIAL_MONTH");
        post(w("/pay-runs/preview"), owner, Map.of("from", "2026-06-01", "to", "2026-06-07", "workerIds", List.of(w1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MONTHLY_NEEDS_WHOLE_MONTHS"));
    }

    @Test
    void pieceWorkAndExtrasAreAddedAndRounded() throws Exception {
        long w1 = worker("Lakshmi", "PIECE", 3.5, "2026-07-01", "kg");
        post(w("/piece-work"), owner, Map.of("workerId", w1, "workDate", "2026-07-02", "quantity", 120.5, "costObjectId", samba)).andExpect(status().isCreated());
        JsonNode run = payRun(Map.of("from", "2026-07-01", "to", "2026-07-07",
                "extras", List.of(Map.of("workerId", w1, "amount", 100, "reason", "Food allowance"))));
        money(run.at("/summary/totalAmount"), "521.75");                 // 120.5 x 3.50 = 421.75, plus 100
        assertThat(accountBalance("5100", samba)).isEqualByComparingTo("421.75");
    }

    private java.math.BigDecimal accountBalance(String code, long costObject) {
        return jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = ? and account_code = ? and cost_object_id = ?",
                java.math.BigDecimal.class, tenant, code, costObject);
    }

    @Test
    void paidDaysAreLockedUntilThePayRunIsReversedAndRunsCannotOverlap() throws Exception {
        long w1 = worker("Kumar", "DAILY", 500, "2026-07-01", null);
        day(w1, "2026-07-01", "PRESENT", samba);
        long run = payRun(Map.of("from", "2026-07-01", "to", "2026-07-07")).at("/summary/id").asLong();
        put(w("/attendance/2026-07-01"), owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "ABSENT"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_LOCKED"));
        post(w("/pay-runs"), owner, Map.of("from", "2026-07-05", "to", "2026-07-10", "workerIds", List.of(w1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAY_RUN_OVERLAP"));
        post(w("/workers/" + w1 + "/rates"), owner, Map.of("effectiveFrom", "2026-07-02", "rate", 600))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RATE_IN_PAID_PERIOD"));
        post(f("/documents/" + run + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        put(w("/attendance/2026-07-01"), owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "ABSENT")))).andExpect(status().isOk());
        money(body(get(w("/workers/" + w1), owner)).get("owed"), "0");
    }

    @Test
    void advancesAreRecoveredAtPaymentWithinWhatIsOwedAndRecordsReverseInOrder() throws Exception {
        long w1 = worker("Kumar", "DAILY", 500, "2026-07-01", null);
        long adv = id(post(w("/advances"), owner, Map.of("workerId", w1, "date", "2026-07-01", "amount", 1500, "financialAccountId", cash))
                .andExpect(status().isCreated()));
        for (int d = 1; d <= 4; d++) day(w1, "2026-07-0" + d, "PRESENT", null);
        long run = payRun(Map.of("from", "2026-07-01", "to", "2026-07-07")).at("/summary/id").asLong();   // 2,000 owed
        post(w("/wage-payments"), owner, Map.of("date", "2026-07-07", "financialAccountId", cash, "payments",
                List.of(Map.of("workerId", w1, "cash", 0, "advanceRecovered", 1600)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MORE_THAN_ADVANCE"));
        post(w("/wage-payments"), owner, Map.of("date", "2026-07-07", "financialAccountId", cash, "payments",
                List.of(Map.of("workerId", w1, "cash", 600, "advanceRecovered", 1500)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WAGES_OVERPAID"));
        JsonNode paid = body(post(w("/wage-payments"), owner, Map.of("date", "2026-07-07", "financialAccountId", cash, "payments",
                List.of(Map.of("workerId", w1, "cash", 500, "advanceRecovered", 1500)))).andExpect(status().isCreated()));
        post(f("/documents/" + run + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WAGES_ALREADY_PAID"));
        post(f("/documents/" + adv + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADVANCE_ALREADY_RECOVERED"));
        // A worker with a balance cannot leave; once settled they can
        post(f("/documents/" + paid.get(0).get("documentId").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        patch(w("/workers/" + w1), owner, Map.of("leftOn", "2026-07-07")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKER_HAS_BALANCE"));
        post(f("/documents/" + run + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        post(f("/documents/" + adv + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        patch(w("/workers/" + w1), owner, Map.of("leftOn", "2026-07-10")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void retriesWithTheSameKeyRecordOnce() throws Exception {
        long w1 = worker("Kumar", "DAILY", 500, "2026-07-01", null);
        day(w1, "2026-07-01", "PRESENT", null);
        Map<String, Object> body = Map.of("from", "2026-07-01", "to", "2026-07-07");
        for (int i = 0; i < 2; i++) {
            mvc.perform(MockMvcRequestBuilders.post(w("/pay-runs")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "run-1")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isCreated());
        }
        Map<String, Object> pay = Map.of("date", "2026-07-07", "financialAccountId", cash, "payments", List.of(Map.of("workerId", w1, "cash", 500, "advanceRecovered", 0)));
        for (int i = 0; i < 2; i++) {
            mvc.perform(MockMvcRequestBuilders.post(w("/wage-payments")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "pay-1")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(pay))).andExpect(status().isCreated());
        }
        assertThat(jdbc.queryForObject("select count(*) from sf_finance_documents where tenant_id = ? and doc_type in ('PAY_RUN', 'WAGE_PAYMENT')",
                Long.class, tenant)).isEqualTo(2);
        money(body(get(w("/workers/" + w1), owner)).get("owed"), "0");
    }

    @Test
    void attendanceRulesAndMonthSheet() throws Exception {
        long w1 = worker("Kumar", "DAILY", 500, "2026-07-05", null);
        put(w("/attendance/2026-07-04"), owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_EMPLOYED"));
        put(w("/attendance/" + LocalDate.now().plusDays(1)), owner, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT"))))
                .andExpect(status().isBadRequest());
        day(w1, "2026-07-05", "PRESENT", samba);
        day(w1, "2026-07-06", "HALF_DAY", null);
        put(w("/attendance/2026-07-06"), owner, Map.of("entries", List.of(Map.of("workerId", w1)))).andExpect(status().isOk());   // cleared
        JsonNode sheet = body(get(w("/attendance-sheet?month=2026-07"), owner));
        money(sheet.at("/workers/0/days"), "1");
        JsonNode muster = body(get(w("/attendance/2026-07-05"), owner));
        assertThat(muster.at("/workers/0/costObjectName").asText()).isEqualTo("Samba");
        assertThat(muster.at("/workers/0/paidByBill").asBoolean()).isFalse();

        // G7: the same day also paid as a labour bill to the worker is flagged on the muster
        long party = body(get(w("/workers/" + w1), owner)).get("partyId").asLong();
        invoice("bills", "2026-07-05", party, null, line("LABOUR", 500, samba));
        assertThat(body(get(w("/attendance/2026-07-05"), owner)).at("/workers/0/paidByBill").asBoolean()).isTrue();
        assertThat(body(get(w("/attendance/2026-07-06"), owner)).at("/workers/0/paidByBill").asBoolean()).isFalse();
    }

    @Test
    void permissionsFollowTheRoles() throws Exception {
        long w1 = worker("Kumar", "DAILY", 500, "2026-07-01", null);
        User supervisor = newUser("wf-supervisor");
        addMember(owner, tenant, supervisor, "FIELD_SUPERVISOR");
        User accountant = newUser("wf-accountant");
        addMember(owner, tenant, accountant, "ACCOUNTANT");
        User worker = newUser("wf-worker");
        addMember(owner, tenant, worker, "WORKER");
        put(w("/attendance/2026-07-01"), supervisor, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT")))).andExpect(status().isOk());
        post(w("/pay-runs/preview"), supervisor, Map.of("from", "2026-07-01", "to", "2026-07-07")).andExpect(status().isForbidden());
        post(w("/pay-runs"), accountant, Map.of("from", "2026-07-01", "to", "2026-07-07")).andExpect(status().isCreated());
        put(w("/attendance/2026-07-02"), accountant, Map.of("entries", List.of(Map.of("workerId", w1, "status", "PRESENT")))).andExpect(status().isForbidden());
        get(w("/workers"), worker).andExpect(status().isForbidden());
        put("/api/v1/tenants/" + tenant + "/modules/WORKFORCE", owner, Map.of("enabled", false)).andExpect(status().isOk());
        get(w("/workers"), owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }
}
