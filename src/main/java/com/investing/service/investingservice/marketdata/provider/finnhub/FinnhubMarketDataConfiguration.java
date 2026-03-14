package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investing.service.investingservice.marketdata.provider.MarketDataProvider;
import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinnhubMarketDataProperties.class)
public class FinnhubMarketDataConfiguration {

    @Bean
    MarketDataProvider marketDataProvider(
            WebClient.Builder webClientBuilder,
            FinnhubMarketDataProperties properties,
            FinnhubDataCache finnhubDataCache
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getReadTimeout());

        WebClient webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        return new FinnhubMarketDataProvider(webClient, properties, finnhubDataCache);
    }

    @Bean
    @ConditionalOnMissingBean(FinnhubDataCache.class)
    FinnhubDataCache finnhubDataCache(
            FinnhubMarketDataProperties properties,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        if (!properties.getCache().isEnabled()) {
            return new NoOpFinnhubDataCache();
        }
        return new RedisFinnhubDataCache(stringRedisTemplate, objectMapper, properties);
    }
}
