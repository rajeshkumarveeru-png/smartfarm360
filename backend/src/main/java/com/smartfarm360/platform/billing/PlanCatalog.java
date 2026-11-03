package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The plans, their limits and their prices (billing design, section 3, P1). Prices are data: each has a date it
 * applies from, excludes GST, and a change applies to renewals from that date.
 */
@Service
public class PlanCatalog {
    public static final List<String> PERIODS = List.of("MONTHLY", "YEARLY");

    private final JdbcClient jdbc;

    public PlanCatalog(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Limit(String code, Integer value) {}

    public record Price(BigDecimal amount, LocalDate validFrom) {}

    /** {@code next} is a price change already announced, or null. */
    public record PeriodPrice(String period, Price current, Price next) {}

    public record Plan(String code, String name, boolean paid, int sortOrder, List<Limit> limits, List<PeriodPrice> prices) {}

    /** The plans an owner can see: the free plan and the paid ones (not the early farm businesses' plan). */
    public List<Plan> plans(LocalDate today) {
        List<Map<String, Object>> rows = jdbc.sql("select code, name, paid, sort_order from sf_plans where code <> 'FREE_LEGACY' order by sort_order").query().listOfRows();
        return rows.stream().map(r -> plan(r, today)).toList();
    }

    public Plan plan(String code, LocalDate today) {
        Map<String, Object> r = jdbc.sql("select code, name, paid, sort_order from sf_plans where code = :c").param("c", code).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PLAN", "Unknown plan."));
        return plan(r, today);
    }

    private Plan plan(Map<String, Object> r, LocalDate today) {
        String code = (String) r.get("code");
        Map<String, Integer> values = jdbc.sql("select limit_code, limit_value from sf_plan_limits where plan_code = :c").param("c", code)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2))).list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // A limit the plan does not list is unlimited
        List<Limit> limits = List.of("MEMBERS", "FARMS", "EXPORTS_PER_DAY", "STORAGE_MB").stream().map(l -> new Limit(l, values.get(l))).toList();
        boolean paid = (Boolean) r.get("paid");
        List<PeriodPrice> prices = !paid ? List.of() : PERIODS.stream().map(p -> new PeriodPrice(p, price(code, p, today).orElse(null), next(code, p, today).orElse(null))).toList();
        return new Plan(code, (String) r.get("name"), paid, ((Number) r.get("sort_order")).intValue(), limits, prices);
    }

    /** The price in force on a day, before GST. */
    public Optional<Price> price(String plan, String period, LocalDate on) {
        return jdbc.sql("select amount, valid_from from sf_plan_prices where plan_code = :p and period = :per and valid_from <= :d order by valid_from desc limit 1")
                .param("p", plan).param("per", period).param("d", Date.valueOf(on))
                .query((rs, i) -> new Price(rs.getBigDecimal(1), rs.getDate(2).toLocalDate())).optional();
    }

    public BigDecimal requirePrice(String plan, String period, LocalDate on) {
        return price(plan, period, on).map(Price::amount).orElseThrow(() -> ApiException.badRequest("PLAN_NOT_FOR_SALE", "This plan has no price yet."));
    }

    private Optional<Price> next(String plan, String period, LocalDate today) {
        return jdbc.sql("select amount, valid_from from sf_plan_prices where plan_code = :p and period = :per and valid_from > :d order by valid_from limit 1")
                .param("p", plan).param("per", period).param("d", Date.valueOf(today))
                .query((rs, i) -> new Price(rs.getBigDecimal(1), rs.getDate(2).toLocalDate())).optional();
    }

    public boolean paid(String plan) {
        return jdbc.sql("select paid from sf_plans where code = :c").param("c", plan).query(Boolean.class).optional()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PLAN", "Unknown plan."));
    }

    public int rank(String plan) {
        return jdbc.sql("select sort_order from sf_plans where code = :c").param("c", plan).query(Integer.class).single();
    }

    public String name(String plan) {
        return jdbc.sql("select name from sf_plans where code = :c").param("c", plan).query(String.class).single();
    }

    public Gst.Rates rates(LocalDate on) {
        Map<String, BigDecimal> r = jdbc.sql("""
                        select distinct on (component) component, rate from sf_tax_rates where code = 'GST_SAAS' and valid_from <= :d
                        order by component, valid_from desc""").param("d", Date.valueOf(on))
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getBigDecimal(2))).list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Gst.Rates(r.get("CGST"), r.get("SGST"), r.get("IGST"));
    }

    static void requirePeriod(String period) {
        if (!PERIODS.contains(period)) throw ApiException.badRequest("UNKNOWN_PERIOD", "Choose monthly or yearly.");
    }
}
