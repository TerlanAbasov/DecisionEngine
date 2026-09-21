package com.quant.finance.decision.live;

import com.quant.finance.decision.error.AlpacaApiException;
import feign.FeignException;

import java.util.function.Supplier;

/** How the Alpaca gateways call the Feign clients: failures become {@link AlpacaApiException}; reads are retried. */
final class AlpacaCalls {
    private AlpacaCalls() {}

    private static final int READ_ATTEMPTS = 3;

    /** A read, retried on rate limits, server errors and network failures. Never use for anything that changes state. */
    static <T> T read(Supplier<T> call) {
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

    /** One attempt: no retry, so a request that changes state is never sent twice. */
    static <T> T once(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException e) {
            throw translate(e);
        }
    }

    static void once(Runnable call) {
        once(() -> { call.run(); return null; });
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
