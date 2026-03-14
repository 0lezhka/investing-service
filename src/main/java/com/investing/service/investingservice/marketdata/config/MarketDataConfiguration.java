package com.investing.service.investingservice.marketdata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class MarketDataConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
