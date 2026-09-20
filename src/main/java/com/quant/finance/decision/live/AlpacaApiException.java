package com.quant.finance.decision.live;

/** Alpaca answered with an error (or could not be reached: {@code status} 0). {@code message} is Alpaca's own text. */
public class AlpacaApiException extends RuntimeException {
    private final int status;

    public AlpacaApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AlpacaApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() { return status; }

    /** Worth retrying a read: rate limited, server error, or no answer at all. */
    public boolean transientFailure() { return status == 0 || status == 429 || status >= 500; }
}
