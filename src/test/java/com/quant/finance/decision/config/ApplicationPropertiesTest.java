package com.quant.finance.decision.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Each profile's yml binds into {@link ApplicationProperties}; the values checked differ from the class defaults, so a broken binding fails. */
class ApplicationPropertiesTest {

    private static ApplicationProperties bind(String yml) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load(yml, new ClassPathResource(yml))
                .forEach(env.getPropertySources()::addFirst);
        return Binder.get(env)
                .bind("application", Bindable.ofInstance(new ApplicationProperties()))
                .get();
    }

    @Test
    void bindsTheLocalProfile() throws IOException {
        ApplicationProperties properties = bind("application-local.yml");
        assertEquals("1Min", properties.getAlpaca().getTimeframe());
        assertEquals(2.0, properties.getDefaultHistoryYears());
        assertEquals(60, properties.getAutotrade().getTickSeconds());
        assertEquals("http://localhost:8081", properties.getExecutionEngine().getUrl());
        assertEquals("http://localhost:5173", properties.getCorsAllowedOrigins());
        assertFalse(properties.getAlpaca().getApiKeyId().isBlank());
    }

    @Test
    void bindsTheProdProfile() throws IOException {
        ApplicationProperties properties = bind("application-prod.yml");
        assertEquals("1Min", properties.getAlpaca().getTimeframe());
        assertEquals("http://localhost:8083", properties.getCorsAllowedOrigins());
        assertEquals("http://localhost:8081", properties.getExecutionEngine().getUrl());
    }
}
