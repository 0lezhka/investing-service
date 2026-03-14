package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.InvestingCliApplication;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubMarketDataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InvestingCliApplicationIT {

    @Test
    void startsCliContextAndExposesDispatcher() {
        try (ConfigurableApplicationContext context = startCliContext()) {
            assertNotNull(context.getBean(RebalanceCliDispatcher.class));
        }
    }

    @Test
    void bindsFinnhubApiKeyInCliContext() {
        try (ConfigurableApplicationContext context = startCliContext()) {
            FinnhubMarketDataProperties properties = context.getBean(FinnhubMarketDataProperties.class);
            assertEquals("test-cli-api-key", properties.getApiKey());
        }
    }

    private ConfigurableApplicationContext startCliContext() {
        return new SpringApplicationBuilder(InvestingCliApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "FINNHUB_API_KEY=test-cli-api-key",
                        "market-data.finnhub.cache.enabled=false"
                )
                .run();
    }
}
