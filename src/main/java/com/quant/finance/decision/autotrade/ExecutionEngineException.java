package com.quant.finance.decision.autotrade;

/** ExecutionEngine refused a command or could not be reached; the message says which. */
public class ExecutionEngineException extends RuntimeException {
    public ExecutionEngineException(String message) {
        super(message);
    }
}
