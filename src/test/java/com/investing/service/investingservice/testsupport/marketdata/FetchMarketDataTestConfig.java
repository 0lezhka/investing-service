package com.investing.service.investingservice.testsupport.marketdata;

import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubMarketDataProperties;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubDataCache;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubMarketDataProvider;
import com.investing.service.investingservice.marketdata.service.FetchMarketDataUseCase;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration(proxyBeanMethods = false)
@Import(FetchMarketDataUseCase.class)
public class FetchMarketDataTestConfig {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    MockWebServer mockWebServer() {
        return new MockWebServer();
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    FinnhubMarketDataProperties finnhubMarketDataProperties(MockWebServer mockWebServer) {
        FinnhubMarketDataProperties properties = new FinnhubMarketDataProperties();
        properties.setBaseUrl(mockWebServer.url("/api/v1").toString());
        properties.setApiKey("test-api-key");
        properties.setEnableMarketStatus(true);
        properties.getCache().setEnabled(true);
        properties.getCache().setBatchSize(10);
        properties.getCache().setMaxConcurrency(1);
        return properties;
    }

    @Bean
    InMemoryFinnhubDataCache finnhubDataCache() {
        return new InMemoryFinnhubDataCache();
    }

    @Bean
    FinnhubMarketDataProvider finnhubMarketDataProvider(
            WebClient.Builder webClientBuilder,
            FinnhubMarketDataProperties properties,
            FinnhubDataCache finnhubDataCache
    ) {
        return new FinnhubMarketDataProvider(
                webClientBuilder.baseUrl(properties.getBaseUrl()).build(),
                properties,
                finnhubDataCache
        );
    }

    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-03-13T20:00:00Z"), ZoneOffset.UTC);
    }
}
