package com.smartfarm360.platform.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * GST for software sold as a service (billing design, section 6, P6). Pure, so each rule is tested on its own.
 * <ul>
 *   <li>The place of supply is the customer's billing state: the seller's own state pays CGST and SGST, any other
 *   state IGST.</li>
 *   <li>Each tax is worked out on the taxable amount and rounded to the paisa, half up.</li>
 *   <li>Invoice numbers run per financial year (April to March), for example {@code SF/2026-27/000123}.</li>
 * </ul>
 */
public final class Gst {
    private Gst() {}

    /** GST state and union territory codes (the first two digits of a GSTIN). */
    public static final Map<String, String> STATES;

    static {
        Map<String, String> s = new TreeMap<>();
        s.put("01", "Jammu and Kashmir"); s.put("02", "Himachal Pradesh"); s.put("03", "Punjab"); s.put("04", "Chandigarh");
        s.put("05", "Uttarakhand"); s.put("06", "Haryana"); s.put("07", "Delhi"); s.put("08", "Rajasthan"); s.put("09", "Uttar Pradesh");
        s.put("10", "Bihar"); s.put("11", "Sikkim"); s.put("12", "Arunachal Pradesh"); s.put("13", "Nagaland"); s.put("14", "Manipur");
        s.put("15", "Mizoram"); s.put("16", "Tripura"); s.put("17", "Meghalaya"); s.put("18", "Assam"); s.put("19", "West Bengal");
        s.put("20", "Jharkhand"); s.put("21", "Odisha"); s.put("22", "Chhattisgarh"); s.put("23", "Madhya Pradesh"); s.put("24", "Gujarat");
        s.put("26", "Dadra and Nagar Haveli and Daman and Diu"); s.put("27", "Maharashtra"); s.put("29", "Karnataka"); s.put("30", "Goa");
        s.put("31", "Lakshadweep"); s.put("32", "Kerala"); s.put("33", "Tamil Nadu"); s.put("34", "Puducherry");
        s.put("35", "Andaman and Nicobar Islands"); s.put("36", "Telangana"); s.put("37", "Andhra Pradesh"); s.put("38", "Ladakh");
        STATES = java.util.Collections.unmodifiableMap(s);
    }

    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** A GSTIN in the right format, for a known state, whose last character is its check digit. */
    public static boolean validGstin(String gstin) {
        if (gstin == null || !GSTIN.matcher(gstin).matches() || !STATES.containsKey(gstin.substring(0, 2))) return false;
        return checkDigit(gstin.substring(0, 14)) == gstin.charAt(14);
    }

    /** The GSTIN check digit: base-36 weighted sum of the first 14 characters (weights 1, 2, 1, 2, …). */
    public static char checkDigit(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = CHARS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += v / 36 + v % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    /** The taxes on one taxable amount. Exactly one of (CGST and SGST) or IGST is non-zero. */
    public record Taxes(BigDecimal taxable, BigDecimal cgstRate, BigDecimal cgst, BigDecimal sgstRate, BigDecimal sgst,
                        BigDecimal igstRate, BigDecimal igst) {
        public BigDecimal total() { return taxable.add(cgst).add(sgst).add(igst); }
        public BigDecimal tax() { return cgst.add(sgst).add(igst); }
        public boolean intraState() { return igstRate.signum() == 0; }
    }

    /** The rates in force, in percent. */
    public record Rates(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {}

    public static Taxes taxes(BigDecimal taxable, String sellerState, String buyerState, Rates rates) {
        BigDecimal amount = money(taxable);
        BigDecimal zero = money(BigDecimal.ZERO);
        if (sellerState.equals(buyerState)) {
            return new Taxes(amount, rates.cgst(), percent(amount, rates.cgst()), rates.sgst(), percent(amount, rates.sgst()), BigDecimal.ZERO, zero);
        }
        return new Taxes(amount, BigDecimal.ZERO, zero, BigDecimal.ZERO, zero, rates.igst(), percent(amount, rates.igst()));
    }

    /**
     * A refund's credit note: the refunded share of each tax, rounded, with the taxable amount as the rest, so the
     * credit note's total is exactly the refund.
     */
    public static Taxes share(Taxes invoice, BigDecimal refund) {
        BigDecimal r = money(refund);
        BigDecimal ratio = r.divide(invoice.total(), 10, RoundingMode.HALF_UP);
        BigDecimal cgst = money(invoice.cgst().multiply(ratio));
        BigDecimal sgst = money(invoice.sgst().multiply(ratio));
        BigDecimal igst = money(invoice.igst().multiply(ratio));
        return new Taxes(r.subtract(cgst).subtract(sgst).subtract(igst), invoice.cgstRate(), cgst, invoice.sgstRate(), sgst, invoice.igstRate(), igst);
    }

    /** The financial year of a date, for example {@code 2026-27} for 28 November 2026. */
    public static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    public static String number(String prefix, String financialYear, long n) {
        return prefix + "/" + financialYear + "/" + String.format("%06d", n);
    }

    public static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal percent(BigDecimal amount, BigDecimal rate) {
        return money(amount.multiply(rate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    }
}
