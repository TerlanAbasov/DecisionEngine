package com.quant.finance.decision.autotrade;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** Sends a {@link TradeCommand} to ExecutionEngine and says what became of it; a command is sent once, never retried. */
@Component
@Slf4j
public class CommandSender {

    /** How a send ended: {@code status} is SENT, REJECTED or FAILED; {@code message} is ExecutionEngine's reply or the error. */
    record SendResult(CommandStatus status, String message) {}

    private final ExecutionEngineApi api;
    private final URI baseUrl;

    public CommandSender(ExecutionEngineApi api, @Value("${decision.execution-engine.url:}") String url) {
        this.api = api;
        this.baseUrl = parse(url);
    }

    /** Whether {@code decision.execution-engine.url} is set to a usable http(s) address; nothing is sent without it. */
    public boolean isConfigured() {
        return baseUrl != null;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (web && uri.getHost() != null) return uri;
        } catch (IllegalArgumentException e) {
            // reported below
        }
        log.error("decision.execution-engine.url '{}' is not an http(s) address: ExecutionEngine is treated as not configured", url);
        return null;
    }

    /** Sends the command; a delivery problem is reported in the result, not thrown. */
    SendResult send(TradeCommand command) {
        if (!isConfigured())
            return new SendResult(CommandStatus.FAILED, "ExecutionEngine is not configured: set decision.execution-engine.url");
        try {
            String reply = api.command(baseUrl, command);
            log.info("ExecutionEngine {} {} x{} ({}): {}", command.command(), command.identifier(), command.quantity(), command.strategy(), reply);
            return new SendResult(isRefusal(reply) ? CommandStatus.REJECTED : CommandStatus.SENT, reply);
        } catch (FeignException e) {
            if (e.status() >= 400) {
                log.warn("ExecutionEngine refused {} {}: HTTP {}", command.command(), command.identifier(), e.status());
                return new SendResult(CommandStatus.REJECTED, "HTTP " + e.status() + bodyOf(e));
            }
            log.warn("No answer from ExecutionEngine for {} {}: {}", command.command(), command.identifier(), e.getMessage());
            return new SendResult(CommandStatus.FAILED, "No answer from ExecutionEngine (" + e.getMessage() + "); the command may or may not have arrived");
        } catch (RuntimeException e) {
            log.error("Could not send {} {} to ExecutionEngine", command.command(), command.identifier(), e);
            return new SendResult(CommandStatus.FAILED, String.valueOf(e.getMessage()));
        }
    }

    /** Sends the command for a caller that wants an error instead of a result; returns ExecutionEngine's reply. */
    String sendOrThrow(TradeCommand command) {
        if (!isConfigured())
            throw new IllegalStateException("ExecutionEngine is not configured: set decision.execution-engine.url (env EXECUTION_ENGINE_URL)");
        SendResult result = send(command);
        if (result.status() != CommandStatus.SENT) throw new ExecutionEngineException(result.message());
        return result.message();
    }

    /** ExecutionEngine reports its own failures as text in a 200 reply: "❌ Error while executing command: …", "Unknown command : …". */
    private static boolean isRefusal(String reply) {
        if (reply == null) return false;
        String text = reply.trim().toLowerCase(Locale.ROOT);
        return text.contains("❌") || text.startsWith("unknown command");
    }

    private static String bodyOf(FeignException e) {
        String body = e.contentUTF8();
        return body == null || body.isBlank() ? "" : ": " + body.trim();
    }
}
