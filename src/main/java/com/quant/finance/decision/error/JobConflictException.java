package com.quant.finance.decision.error;

import com.quant.finance.decision.dto.Dtos.JobDto;

public class JobConflictException extends RuntimeException {
    private final transient JobDto active;

    public JobConflictException(JobDto active) {
        super("A backtest is already running");
        this.active = active;
    }

    public JobDto active() { return active; }
}
