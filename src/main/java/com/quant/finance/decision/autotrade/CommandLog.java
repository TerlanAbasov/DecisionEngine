package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.CommandSender.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/** The record of every command, which is also what keeps a bar from triggering one twice. */
@Component
@Slf4j
@RequiredArgsConstructor
class CommandLog {

    private final AutoTradeCommandRepository repo;
    private final Clock clock;

    /**
     * Writes the command for {@code flip}; empty when that bar already has one, which means it must not be sent again. This is the ordinary
     * path after a restart (every open bar looks due again) and happens routinely, so it is checked for up front — hitting the unique key
     * instead would still be caught, but not before Hibernate logged the constraint violation as a warning and an error of its own.
     */
    Optional<AutoTradeCommandEntity> record(Flip flip, AutoTradeSettings settings, CommandStatus status, String response) {
        if (repo.existsByStrategyAndSymbolAndTimeframeAndBarOpen(flip.strategy(), flip.symbol(), flip.timeframe().name(), flip.barOpen())) {
            log.debug("{} {} {} bar {} already has a command", flip.strategy(), flip.symbol(), flip.timeframe(), flip.barOpen());
            return Optional.empty();
        }
        AutoTradeCommandEntity row = new AutoTradeCommandEntity();
        row.setCreatedAt(clock.instant());
        row.setStrategy(flip.strategy());
        row.setSymbol(flip.symbol());
        row.setTimeframe(flip.timeframe().name());
        row.setBarOpen(flip.barOpen());
        row.setSide(flip.side().name());
        row.setQuantity(settings.quantity());
        row.setOrderType(settings.orderType().name());
        row.setLimitPrice(settings.orderType() == AutoTradeSettings.OrderType.LMT ? flip.close() : null);
        row.setStatus(status.name());
        row.setResponse(truncated(response));
        try {
            return Optional.of(repo.saveAndFlush(row));
        } catch (DataIntegrityViolationException e) {
            // the exists() check above just missed a command written between it and this insert — vanishingly rare (ticks never overlap),
            // but the unique key is the actual guarantee; this is only its backstop.
            log.debug("{} {} {} bar {} got a command just before this one did", flip.strategy(), flip.symbol(), flip.timeframe(), flip.barOpen());
            return Optional.empty();
        }
    }

    void finish(AutoTradeCommandEntity row, SendResult result) {
        row.setStatus(result.status().name());
        row.setResponse(truncated(result.message()));
        repo.save(row);
    }

    Page<AutoTradeCommandEntity> page(int page, int size) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(200, size))));
    }

    private static String truncated(String text) {
        if (text == null) return null;
        return text.length() <= AutoTradeCommandEntity.RESPONSE_LENGTH ? text : text.substring(0, AutoTradeCommandEntity.RESPONSE_LENGTH - 1) + "…";
    }
}
