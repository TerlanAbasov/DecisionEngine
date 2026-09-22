package com.quant.finance.decision.client;

import com.quant.finance.decision.error.AlpacaApiException;
import feign.FeignException;

import java.util.function.Supplier;

/** How {@link AlpacaClient} is called: failures become {@link AlpacaApiException}; reads are retried. */
public final class AlpacaCalls {
    private AlpacaCalls() {}

    private static final int READ_ATTEMPTS = 3;

    /** A read, retried on rate limits, server errors and network failures. */
    public static <T> T read(Supplier<T> call) {
        AlpacaApiException last = null;
        for (int attempt = 1; attempt <= READ_ATTEMPTS; attempt++) {
            try {
                return once(call);
            } catch (AlpacaApiException e) {
                last = e;
                if (!e.transientFailure() || attempt == READ_ATTEMPTS) throw e;
                pause(400L * attempt);
            }
        }
        throw last;
    }

    private static <T> T once(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException e) {
            throw translate(e);
        }
    }

    /** Feign reports "no answer" (timeout, refused connection) and undecodable bodies as FeignException with no HTTP error status. */
    private static AlpacaApiException translate(FeignException e) {
        int status = e.status();
        return status >= 400 ? new AlpacaApiException(status, e.getMessage())
                : new AlpacaApiException("Could not reach Alpaca: " + e.getMessage(), e);
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
