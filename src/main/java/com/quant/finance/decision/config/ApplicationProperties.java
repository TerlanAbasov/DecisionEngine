package com.quant.finance.decision.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@ConfigurationProperties(prefix = "application")
@Configuration
public class ApplicationProperties {

    private final Backtest backtest = new Backtest();
    private final Data data = new Data();
    private final Alpaca alpaca = new Alpaca();
    private final ExecutionEngine executionEngine = new ExecutionEngine();
    private final Autotrade autotrade = new Autotrade();
    private double defaultHistoryYears = 6.5;
    private String corsAllowedOrigins = "http://localhost:5173";

    @Setter
    @Getter
    public static class Backtest {
        private int threads = 50;
        private boolean tracePerSymbol = true;
        private int runAllMaxDays = 400;
    }

    @Setter
    @Getter
    public static class Data {
        private long freshDays = 4;
    }

    @Setter
    @Getter
    public static class Alpaca {
        private String apiKeyId = "";
        private String apiSecretKey = "";
        private String dataBaseUrl = "https://data.alpaca.markets";
        private String feed = "iex";
        private String timeframe = "1Day";
    }

    @Setter
    @Getter
    public static class ExecutionEngine {
        private String url;
    }

    @Setter
    @Getter
    public static class Autotrade {
        private int tickSeconds = 30;
    }
}
