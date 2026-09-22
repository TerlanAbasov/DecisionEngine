package com.quant.finance.decision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The wall clock, injected wherever "now" needs to be a bean (so tests can fix it). Everything in this app reasons in UTC. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
