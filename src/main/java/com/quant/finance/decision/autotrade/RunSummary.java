package com.quant.finance.decision.autotrade;

import java.time.Instant;

/** What the job's last run that had something to look at did: how many (symbol, timeframe) pairs it checked, the flips it found and how their commands ended. */
public record RunSummary(Instant at, int checked, int errors, int flips, int sent, int rejected, int failed, int skipped) {}
