package com.quant.finance.decision.job;

public enum JobStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() { return this != RUNNING; }
}
