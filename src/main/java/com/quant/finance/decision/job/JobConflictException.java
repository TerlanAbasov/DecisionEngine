package com.quant.finance.decision.job;

import com.quant.finance.decision.dto.Dtos.JobDto;

/** A backtest is already running; carries it so the caller can attach to it instead. */
public class JobConflictException extends RuntimeException {
    private final transient JobDto active;

    public JobConflictException(JobDto active) {
        super("A backtest is already running");
        this.active = active;
    }

    public JobDto active() { return active; }
}
