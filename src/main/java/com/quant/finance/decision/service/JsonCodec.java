package com.quant.finance.decision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Thin wrapper so services can (de)serialise JSON stored in text columns. */
@Component
public class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    /** A JSON object of numbers (params, metrics, per-symbol returns); null / blank -> empty. */
    public Map<String, Double> readDoubleMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }

    /** Parses a stored JSON document into {@code type}; null / blank -> null (nothing stored). */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }

    public double[] readDoubles(String json) {
        if (json == null || json.isBlank()) {
            return new double[0];
        }
        try {
            List<Double> values = mapper.readValue(json, new TypeReference<List<Double>>() {});
            double[] result = new double[values.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = values.get(i);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }
}
