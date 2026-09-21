package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.CommandSender.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sends the commands for a run's flips, one at a time. Before each one it checks the job is still switched on and that the bar has no command yet,
 * and it writes the command down before sending, so nothing goes out twice. Whatever ExecutionEngine answers is recorded; nothing is retried.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class CommandForwarder {

    /** How many commands ended each way. */
    record Outcome(int sent, int rejected, int failed, int skipped) {}

    private final AutoTradeConfigService config;
    private final CommandLog commands;
    private final CommandSender sender;

    Outcome forward(List<Flip> flips) {
        int sent = 0, rejected = 0, failed = 0, skipped = 0, attempts = 0;
        List<Flip> ordered = flips.stream().sorted(Comparator.comparing(Flip::symbol).thenComparing(Flip::strategy)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            Flip flip = ordered.get(i);
            AutoTradeSettings settings = config.current();
            if (!settings.enabled()) {
                log.info("Auto-trading was switched off: {} command(s) not sent", ordered.size() - i);
                break;
            }
            if (attempts >= settings.maxCommandsPerRun()) {
                String reason = "Over the limit of " + settings.maxCommandsPerRun() + " commands per run";
                if (commands.record(flip, settings, CommandStatus.SKIPPED, reason).isPresent()) skipped++;
                continue;
            }
            Optional<AutoTradeCommandEntity> row = commands.record(flip, settings, CommandStatus.PENDING, null);
            if (row.isEmpty()) continue;                                 // this bar already has its command
            attempts++;

            SendResult result = sender.send(TradeCommand.of(flip, settings));
            commands.finish(row.get(), result);
            switch (result.status()) {
                case SENT -> sent++;
                case REJECTED -> rejected++;
                default -> failed++;
            }
        }
        return new Outcome(sent, rejected, failed, skipped);
    }
}
