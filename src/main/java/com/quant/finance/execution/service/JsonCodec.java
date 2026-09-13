package com.quant.finance.execution.service;

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
