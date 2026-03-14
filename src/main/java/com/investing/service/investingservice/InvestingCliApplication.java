package com.investing.service.investingservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.investing.service.investingservice.marketdata.config",
        "com.investing.service.investingservice.marketdata.service",
        "com.investing.service.investingservice.marketdata.provider.finnhub",
        "com.investing.service.investingservice.rebalancing.service",
        "com.investing.service.investingservice.rebalancing.cli"
})
public class InvestingCliApplication {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnMissingBean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
