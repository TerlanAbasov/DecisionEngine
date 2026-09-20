package com.quant.finance.decision.repository;

import java.time.Instant;

/** One cached OHLCV bar as a plain value — not a managed entity, so reading bars costs the session nothing. */
public record BarRow(Instant barTime, double open, double high, double low, double close, double volume) {}
