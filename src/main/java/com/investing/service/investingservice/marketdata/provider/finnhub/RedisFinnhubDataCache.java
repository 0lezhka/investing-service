package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class RedisFinnhubDataCache implements FinnhubDataCache {

    private static final Logger log = LoggerFactory.getLogger(RedisFinnhubDataCache.class);
    private static final String QUOTE_KEY_PREFIX = "market-data:finnhub:quote:";
    private static final String PROFILE_KEY_PREFIX = "market-data:finnhub:profile:";
    private static final String MARKET_STATUS_KEY_PREFIX = "market-data:finnhub:market-status:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final FinnhubMarketDataProperties properties;

    public RedisFinnhubDataCache(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            FinnhubMarketDataProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<FinnhubQuoteResponse> getQuote(String ticker) {
        return get(QUOTE_KEY_PREFIX + ticker, FinnhubQuoteResponse.class);
    }

    @Override
    public void putQuote(String ticker, FinnhubQuoteResponse quote) {
        put(QUOTE_KEY_PREFIX + ticker, quote, properties.getCache().getQuoteTtl());
    }

    @Override
    public Optional<FinnhubProfileResponse> getProfile(String ticker) {
        return get(PROFILE_KEY_PREFIX + ticker, FinnhubProfileResponse.class);
    }

    @Override
    public void putProfile(String ticker, FinnhubProfileResponse profile) {
        put(PROFILE_KEY_PREFIX + ticker, profile, properties.getCache().getProfileTtl());
    }

    @Override
    public Optional<FinnhubMarketStatusResponse> getMarketStatus(String exchange) {
        return get(MARKET_STATUS_KEY_PREFIX + exchange, FinnhubMarketStatusResponse.class);
    }

    @Override
    public void putMarketStatus(String exchange, FinnhubMarketStatusResponse marketStatus) {
        put(MARKET_STATUS_KEY_PREFIX + exchange, marketStatus, properties.getCache().getMarketStatusTtl());
    }

    private <T> Optional<T> get(String key, Class<T> type) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, type));
        } catch (Exception exception) {
            log.warn("Finnhub cache read failed for key {}: {}", key, exception.getMessage());
            return Optional.empty();
        }
    }

    private void put(String key, Object value, Duration ttl) {
        if (value == null) {
            return;
        }

        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            log.warn("Finnhub cache serialization failed for key {}: {}", key, exception.getMessage());
        } catch (Exception exception) {
            log.warn("Finnhub cache write failed for key {}: {}", key, exception.getMessage());
        }
    }
}
