package com.quant.finance.decision.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Reads Alpaca's JSON, which leaves fields out or null when they do not apply. */
public final class AlpacaJson {
    private AlpacaJson() {}

    /** The field's text, or null when it is missing, blank or JSON null. */
    public static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() || "null".equals(value) ? null : value;
    }
}
