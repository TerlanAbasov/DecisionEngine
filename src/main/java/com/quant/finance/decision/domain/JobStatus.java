package com.quant.finance.decision.domain;

public enum JobStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() { return this != RUNNING; }
}
