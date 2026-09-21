package com.quant.finance.decision.live;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;

/** Reads Alpaca's JSON, which sends numbers as strings ("12.5") and leaves fields out or null when they do not apply. */
final class AlpacaJson {
    private AlpacaJson() {}

    /** The field's text, or null when it is missing, blank or JSON null. */
    static String text(JsonNode n, String field) {
        String s = n.path(field).asText("");
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    /** The field as a number; 0 when it is missing or not a number. */
    static double num(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** The field as a point in time; null when it is missing or not a timestamp. */
    static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception e) { return null; }
    }
}
