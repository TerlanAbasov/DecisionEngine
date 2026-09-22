package com.quant.finance.decision.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Reads Alpaca's JSON, which leaves fields out or null when they do not apply. */
public final class AlpacaJson {
    private AlpacaJson() {}

    /** The field's text, or null when it is missing, blank or JSON null. */
    public static String text(JsonNode n, String field) {
        String s = n.path(field).asText("");
        return s.isBlank() || "null".equals(s) ? null : s;
    }
}
