package com.quant.finance.decision.client;

import com.quant.finance.decision.config.ApplicationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The Alpaca key pair used to read market data (for backtesting and auto trading's signal detection). */
@Component
@RequiredArgsConstructor
public class AlpacaCredentials {
    private final ApplicationProperties properties;

    public String keyId() { return trimmed(properties.getAlpaca().getApiKeyId()); }
    public String secret() { return trimmed(properties.getAlpaca().getApiSecretKey()); }
    public String feed() {
        String feed = properties.getAlpaca().getFeed();
        return feed == null || feed.isBlank() ? "iex" : feed.trim();
    }
    public boolean configured() { return !keyId().isBlank() && !secret().isBlank(); }

    private static String trimmed(String value) { return value == null ? "" : value.trim(); }
}
