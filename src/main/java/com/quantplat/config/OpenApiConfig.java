package com.quantplat.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI quantPlatOpenApi() {
        return new OpenAPI().info(new Info()
                .title("QuantPlat API")
                .version("v1")
                .description("Strategy scanning, backtesting, universe management, and market data for QuantPlat."));
    }
}
