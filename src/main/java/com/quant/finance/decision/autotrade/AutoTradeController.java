package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeDtos.CommandDto;
import com.quant.finance.decision.autotrade.AutoTradeDtos.PageDto;
import com.quant.finance.decision.autotrade.AutoTradeDtos.StatusDto;
import com.quant.finance.decision.dto.Dtos.SignalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Switch the job that sends strategy signals to ExecutionEngine on or off, set what it may send, see what it sent, and forward one signal by hand. */
@RestController
@RequestMapping("/api/autotrade")
@Slf4j
@RequiredArgsConstructor
public class AutoTradeController {

    private final AutoTradeConfigService config;
    private final AutoTradeJob job;
    private final CommandSender sender;
    private final CommandLog commands;

    @GetMapping("/status")
    public StatusDto status() {
        return new StatusDto(config.current(), sender.isConfigured(), job.running(), job.lastRun(), job.tickSeconds());
    }

    /** Saves the form fields (quantity, order type, time in force, strategies, symbols, limit); the switch is left as it is. */
    @PutMapping("/settings")
    public AutoTradeSettings putSettings(@RequestBody AutoTradeSettings settings) {
        log.info("PUT /autotrade/settings");
        return config.update(settings);
    }

    /** Switches the job on: only bars that complete from now on can trigger a command. Refused while ExecutionEngine is not configured. */
    @PostMapping("/enable")
    public AutoTradeSettings enable() {
        log.info("POST /autotrade/enable");
        return config.enable();
    }

    @PostMapping("/disable")
    public AutoTradeSettings disable() {
        log.info("POST /autotrade/disable");
        return config.disable();
    }

    @GetMapping("/commands")
    public PageDto<CommandDto> commands(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Page<AutoTradeCommandEntity> found = commands.page(page, size);
        return new PageDto<>(found.stream().map(CommandDto::of).toList(), found.getTotalElements(), found.getNumber(), found.getSize());
    }

    /** Sends one scanned LONG/SHORT signal now with the saved quantity and order type, whether or not the job is on. */
    @PostMapping("/forward")
    public Map<String, String> forward(@RequestBody SignalDto signal) {
        log.info("POST /autotrade/forward — {} {} {}", signal.signal(), signal.symbol(), signal.strategy());
        String reply = sender.sendOrThrow(TradeCommand.of(signal, config.current()));
        return Map.of("status", "sent", "symbol", signal.symbol(), "strategy", signal.strategy(), "response", String.valueOf(reply));
    }
}
