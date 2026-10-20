package com.smartfarm360.inventory;

import java.util.Arrays;
import java.util.stream.Collectors;

/** CSV rows (RFC 4180) that spreadsheet programs open safely: text starting with a formula character is prefixed with '. */
public final class Csv {
    private Csv() {}

    public static String row(String... cells) {
        return Arrays.stream(cells).map(Csv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
