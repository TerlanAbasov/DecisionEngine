package com.quant.finance.decision.autotrade;

/**
 * Where a command stands. SENT: ExecutionEngine accepted it. REJECTED: it answered with a refusal, so nothing was ordered.
 * FAILED: no answer came, so the command may or may not have arrived. SKIPPED: held back here (over the per-run limit). PENDING: being sent.
 */
public enum CommandStatus { PENDING, SENT, REJECTED, FAILED, SKIPPED }
