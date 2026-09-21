package com.quant.finance.decision.controller;

import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.EnsembleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BacktestControllerExportTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private BacktestService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BacktestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BacktestController(service, mock(EnsembleService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void downloadsTheWorkbookAsAnAttachmentNamedAfterTheRunAndFilter() throws Exception {
        byte[] workbook = {'P', 'K', 3, 4};
        when(service.exportTrades(12L, "aapl", "long", "netPnl", "asc")).thenReturn(workbook);

        mvc.perform(get("/api/backtests/12/trades/export").param("symbol", "aapl").param("side", "long")
                        .param("sort", "netPnl").param("dir", "asc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"trades-run-12-AAPL-LONG.xlsx\""))
                .andExpect(content().bytes(workbook));
    }

    @Test
    void anUnfilteredExportIsNamedAfterTheRunOnly() throws Exception {
        when(service.exportTrades(12L, null, null, null, null)).thenReturn(new byte[]{1});
        mvc.perform(get("/api/backtests/12/trades/export"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"trades-run-12.xlsx\""));
    }

    @Test
    void aMissingRunIsANormalErrorNotAnEmptyDownload() throws Exception {
        when(service.exportTrades(99L, null, null, null, null)).thenThrow(new NoSuchElementException("No run 99"));
        mvc.perform(get("/api/backtests/99/trades/export")).andExpect(status().isNotFound());
    }

    @Test
    void badFilterInputIsRejected() throws Exception {
        when(service.exportTrades(12L, null, "UP", null, null)).thenThrow(new IllegalArgumentException("side must be LONG or SHORT, got: UP"));
        mvc.perform(get("/api/backtests/12/trades/export").param("side", "UP")).andExpect(status().isBadRequest());
    }
}
