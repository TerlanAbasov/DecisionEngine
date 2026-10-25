package com.quant.finance.decision.autotrade;

import feign.FeignException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandSender {

    record SendResult(CommandStatus status, String message) {}

    private final ExecutionEngineClient api;

    SendResult send(TradeCommand command) {
        try {
            String reply = api.command(command);
            log.info(
                    "ExecutionEngine {} {} x{} ({}): {}",
                    command.command(),
                    command.identifier(),
                    command.quantity(),
                    command.strategy(),
                    reply);
            return new SendResult(isRefusal(reply) ? CommandStatus.REJECTED : CommandStatus.SENT, reply);
        } catch (FeignException e) {
            if (e.status() >= 400) {
                log.error(
                        "ExecutionEngine refused {} {}: HTTP {}", command.command(), command.identifier(), e.status());
                return new SendResult(CommandStatus.REJECTED, "HTTP " + e.status() + bodyOf(e));
            }
            log.error(
                    "No answer from ExecutionEngine for {} {}: {}",
                    command.command(),
                    command.identifier(),
                    e.getMessage());
            return new SendResult(
                    CommandStatus.FAILED,
                    "No answer from ExecutionEngine (" + e.getMessage() + "); the command may or may not have arrived");
        } catch (RuntimeException e) {
            log.error("Could not send {} {} to ExecutionEngine", command.command(), command.identifier(), e);
            return new SendResult(CommandStatus.FAILED, String.valueOf(e.getMessage()));
        }
    }

    String sendOrThrow(TradeCommand command) {
        SendResult result = send(command);
        if (result.status() != CommandStatus.SENT) {
            throw new ExecutionEngineException(result.message());
        }
        return result.message();
    }

    private static boolean isRefusal(String reply) {
        if (reply == null) {
            return false;
        }
        String text = reply.trim().toLowerCase(Locale.ROOT);
        return text.contains("❌") || text.startsWith("unknown command");
    }

    private static String bodyOf(FeignException exception) {
        String body = exception.contentUTF8();
        return body == null || body.isBlank() ? "" : ": " + body.trim();
    }
}
