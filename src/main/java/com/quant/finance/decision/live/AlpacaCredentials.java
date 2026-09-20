package com.quant.finance.decision.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The Alpaca key pair shared by the market-data and paper-trading clients. */
@Component
public class AlpacaCredentials {
    private final String keyId;
    private final String secret;
    private final String feed;

    public AlpacaCredentials(@Value("${decision.alpaca.api-key-id:}") String keyId,
                             @Value("${decision.alpaca.api-secret-key:}") String secret,
                             @Value("${decision.alpaca.feed:iex}") String feed) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.feed = feed == null || feed.isBlank() ? "iex" : feed.trim();
    }

    public String keyId() { return keyId; }
    public String secret() { return secret; }
    public String feed() { return feed; }
    public boolean configured() { return !keyId.isBlank() && !secret.isBlank(); }
}
