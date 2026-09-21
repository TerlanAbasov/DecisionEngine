package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.autotrade.AutoTradeSettings.OrderType;
import com.quant.finance.decision.autotrade.AutoTradeSettings.TimeInForce;
import com.quant.finance.decision.controller.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AutoTradeControllerTest {

    private static final Instant SINCE = Instant.parse("2026-09-21T14:00:00Z");
    private static final AutoTradeSettings ON = new AutoTradeSettings(true, SINCE, 5, OrderType.MKT, TimeInForce.DAY, List.of(), List.of("AAPL"), 25);

    private AutoTradeConfigService config;
    private AutoTradeJob job;
    private CommandSender sender;
    private CommandLog commands;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        config = mock(AutoTradeConfigService.class);
        job = mock(AutoTradeJob.class);
        sender = mock(CommandSender.class);
        commands = mock(CommandLog.class);
        ObjectMapper bootLike = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(new AutoTradeController(config, job, sender, commands))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(bootLike))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void statusShowsTheSwitchTheConfigurationAndTheLastRun() throws Exception {
        when(config.current()).thenReturn(ON);
        when(sender.isConfigured()).thenReturn(true);
        when(job.lastRun()).thenReturn(new RunSummary(SINCE, 10, 0, 2, 1, 1, 0, 0));
        when(job.tickSeconds()).thenReturn(30);

        mvc.perform(get("/api/autotrade/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(true))
                .andExpect(jsonPath("$.settings.enabledSince").value("2026-09-21T14:00:00Z"))
                .andExpect(jsonPath("$.settings.orderType").value("MKT"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.lastRun.flips").value(2))
                .andExpect(jsonPath("$.checkEverySeconds").value(30));
    }

    @Test
    void enableAndDisableReturnTheNewSettings() throws Exception {
        when(config.enable()).thenReturn(ON);
        when(config.disable()).thenReturn(AutoTradeSettings.defaults());

        mvc.perform(post("/api/autotrade/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/autotrade/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void enablingWhileNotConfiguredIsABadRequestWithTheReason() throws Exception {
        when(config.enable()).thenThrow(new IllegalStateException("ExecutionEngine is not configured: set decision.execution-engine.url"));
        mvc.perform(post("/api/autotrade/enable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ExecutionEngine is not configured: set decision.execution-engine.url"));
    }

    @Test
    void theFormIsSavedThroughTheServiceAndBadInputIsRejected() throws Exception {
        when(config.update(any())).thenReturn(ON);
        String body = "{\"quantity\":5,\"orderType\":\"MKT\",\"tif\":\"DAY\",\"strategyNames\":[],\"symbols\":[\"AAPL\"],\"maxCommandsPerRun\":25}";
        mvc.perform(put("/api/autotrade/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(5));

        when(config.update(any())).thenThrow(new IllegalArgumentException("quantity must be more than 0 and at most 1000000 shares"));
        mvc.perform(put("/api/autotrade/settings").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"quantity\":5", "\"quantity\":0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("quantity must be more than 0 and at most 1000000 shares"));
    }

    @Test
    void commandsArePagedNewestFirst() throws Exception {
        AutoTradeCommandEntity row = new AutoTradeCommandEntity();
        row.setId(9L);
        row.setCreatedAt(SINCE);
        row.setStrategy("rsi");
        row.setSymbol("AAPL");
        row.setTimeframe("M15");
        row.setBarOpen(SINCE);
        row.setSide("BUY");
        row.setQuantity(5);
        row.setOrderType("MKT");
        row.setStatus("SENT");
        row.setResponse("📊 Symbol will be bought");
        when(commands.page(0, 50)).thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/autotrade/commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(9))
                .andExpect(jsonPath("$.items[0].status").value("SENT"))
                .andExpect(jsonPath("$.items[0].side").value("BUY"))
                .andExpect(jsonPath("$.items[0].response").value("📊 Symbol will be bought"));
    }

    private static final String SIGNAL = "{\"strategy\":\"rsi\",\"category\":\"mean\",\"symbol\":\"AAPL\",\"signal\":\"%s\",\"isNew\":true,"
            + "\"bars\":1,\"weight\":1.0,\"close\":101.5,\"date\":\"2026-09-21T14:45:00Z\",\"timeframe\":\"M15\"}";

    @Test
    void aScannedSignalCanBeForwardedByHandUsingTheSavedQuantity() throws Exception {
        when(config.current()).thenReturn(ON);
        when(sender.sendOrThrow(any())).thenReturn("📊 Symbol will be bought");

        mvc.perform(post("/api/autotrade/forward").contentType(MediaType.APPLICATION_JSON).content(String.format(SIGNAL, "LONG")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.response").value("📊 Symbol will be bought"));
        verify(sender).sendOrThrow(argThat(c -> c.command() == TradeCommand.Side.BUY && c.quantity() == 5 && c.identifier().equals("AAPL")));
    }

    @Test
    void aFlatSignalCannotBeForwardedAndAnEngineRefusalIsABadGateway() throws Exception {
        when(config.current()).thenReturn(ON);
        mvc.perform(post("/api/autotrade/forward").contentType(MediaType.APPLICATION_JSON).content(String.format(SIGNAL, "FLAT")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sender);

        when(sender.sendOrThrow(any())).thenThrow(new ExecutionEngineException("❌ Error while executing command: no contract"));
        mvc.perform(post("/api/autotrade/forward").contentType(MediaType.APPLICATION_JSON).content(String.format(SIGNAL, "SHORT")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ExecutionEngine: ❌ Error while executing command: no contract"));
    }
}
