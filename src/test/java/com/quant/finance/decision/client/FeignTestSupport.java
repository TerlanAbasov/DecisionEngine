package com.quant.finance.decision.client;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** A real Spring context holding only the Alpaca Feign client, pointed at a test server — so the tests exercise the actual wiring. */
final class FeignTestSupport implements AutoCloseable {

    @Configuration
    @EnableFeignClients(clients = {AlpacaClient.class})
    @ImportAutoConfiguration({HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class, FeignAutoConfiguration.class})
    static class Config {
        @Bean
        AlpacaCredentials alpacaCredentials(org.springframework.core.env.Environment env) {
            return new AlpacaCredentials(env.getProperty("decision.alpaca.api-key-id", ""),
                    env.getProperty("decision.alpaca.api-secret-key", ""), "iex");
        }
    }

    private final AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

    FeignTestSupport(String dataBase, String keyId, String secret) {
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "decision.alpaca.data-base-url", dataBase,
                "decision.alpaca.api-key-id", keyId,
                "decision.alpaca.api-secret-key", secret)));
        ctx.register(Config.class);
        ctx.refresh();
    }

    AlpacaClient client() { return ctx.getBean(AlpacaClient.class); }

    @Override
    public void close() { ctx.close(); }
}
