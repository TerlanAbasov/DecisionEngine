package com.quant.finance.decision.job;

/** Thrown at a safe point once a job was asked to stop; rolls the surrounding transaction back. */
public class JobCancelledException extends RuntimeException {
    public JobCancelledException() { super("Backtest cancelled"); }
}
