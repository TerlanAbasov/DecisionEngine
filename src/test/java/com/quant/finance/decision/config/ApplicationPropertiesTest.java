package com.quant.finance.decision.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Each profile's yml binds into {@link ApplicationProperties}; the values checked differ from the class defaults, so a broken binding fails. */
class ApplicationPropertiesTest {

    private static ApplicationProperties bind(String yml) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load(yml, new ClassPathResource(yml))
                .forEach(env.getPropertySources()::addFirst);
        return Binder.get(env).bind("application", Bindable.ofInstance(new ApplicationProperties())).get();
    }

    @Test
    void bindsTheLocalProfile() throws IOException {
        ApplicationProperties p = bind("application-local.yml");
        assertEquals("1Min", p.getAlpaca().getTimeframe());
        assertEquals(2.0, p.getDefaultHistoryYears());
        assertEquals(60, p.getAutotrade().getTickSeconds());
        assertEquals("http://localhost:8081", p.getExecutionEngine().getUrl());
        assertEquals("http://localhost:5173", p.getCorsAllowedOrigins());
        assertFalse(p.getAlpaca().getApiKeyId().isBlank());
    }

    @Test
    void bindsTheProdProfile() throws IOException {
        ApplicationProperties p = bind("application-prod.yml");
        assertEquals("1Min", p.getAlpaca().getTimeframe());
        assertEquals("http://localhost:8083", p.getCorsAllowedOrigins());
        assertEquals("http://localhost:8081", p.getExecutionEngine().getUrl());
    }
}
