package com.quant.finance.decision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Thin wrapper so services can (de)serialise JSON stored in text columns. */
@Component
public class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    public Map<String, Double> readParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }

    public Map<String, Double> readMetrics(String json) {
        return readParams(json);
    }

    /** Parses a stored JSON document into {@code type}; null / blank -> null (nothing stored). */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }

    public double[] readDoubles(String json) {
        if (json == null || json.isBlank()) return new double[0];
        try {
            List<Double> l = mapper.readValue(json, new TypeReference<List<Double>>() {});
            double[] a = new double[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            return a;
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }
}
