package com.smartfarm360.platform.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Labels for exported reports, from the catalogs shared with the web and phone apps (ADR 0008), bundled into the jar
 * at build time. Falls back to English, then to the key, like the clients.
 */
@Component
public class ReportLabels {
    private static final Pattern PARAM = Pattern.compile("\\{\\{(\\w+)}}");
    private final Map<String, JsonNode> catalogs;

    public ReportLabels(ObjectMapper mapper) {
        this.catalogs = Map.of("en", load(mapper, "en"), "ta", load(mapper, "ta"));
    }

    private static JsonNode load(ObjectMapper mapper, String lang) {
        try (InputStream in = ReportLabels.class.getResourceAsStream("/i18n/" + lang + ".json")) {
            if (in == null) throw new IllegalStateException("Missing translation catalog i18n/" + lang + ".json");
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable translation catalog " + lang, e);
        }
    }

    /** {@code en} or {@code ta}; anything else is English. */
    public static String language(String requested) { return "ta".equals(requested) ? "ta" : "en"; }

    public String t(String lang, String key) { return t(lang, key, Map.of()); }

    public String t(String lang, String key, Map<String, String> params) {
        String text = lookup(catalogs.get(language(lang)), key);
        if (text == null) text = lookup(catalogs.get("en"), key);
        if (text == null) return key;
        Matcher m = PARAM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(params.getOrDefault(m.group(1), "")));
        m.appendTail(out);
        return out.toString();
    }

    /** True when the key exists in the English catalog (used by tests to keep export labels real). */
    public boolean has(String key) { return lookup(catalogs.get("en"), key) != null; }

    private static String lookup(JsonNode root, String key) {
        JsonNode node = root;
        for (String part : key.split("\\.")) {
            if (node == null) return null;
            node = node.get(part);
        }
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
