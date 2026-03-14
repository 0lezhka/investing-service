package com.investing.service.investingservice.testsupport.marketdata;

import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubDataCache;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubMarketStatusResponse;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubProfileResponse;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubQuoteResponse;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFinnhubDataCache implements FinnhubDataCache {

    private final Map<String, FinnhubQuoteResponse> quotes = new ConcurrentHashMap<>();
    private final Map<String, FinnhubProfileResponse> profiles = new ConcurrentHashMap<>();
    private final Map<String, FinnhubMarketStatusResponse> marketStatuses = new ConcurrentHashMap<>();

    @Override
    public Optional<FinnhubQuoteResponse> getQuote(String ticker) {
        return Optional.ofNullable(quotes.get(ticker));
    }

    @Override
    public void putQuote(String ticker, FinnhubQuoteResponse quote) {
        quotes.put(ticker, quote);
    }

    @Override
    public Optional<FinnhubProfileResponse> getProfile(String ticker) {
        return Optional.ofNullable(profiles.get(ticker));
    }

    @Override
    public void putProfile(String ticker, FinnhubProfileResponse profile) {
        profiles.put(ticker, profile);
    }

    @Override
    public Optional<FinnhubMarketStatusResponse> getMarketStatus(String exchange) {
        return Optional.ofNullable(marketStatuses.get(exchange));
    }

    @Override
    public void putMarketStatus(String exchange, FinnhubMarketStatusResponse marketStatus) {
        marketStatuses.put(exchange, marketStatus);
    }

    public void clear() {
        quotes.clear();
        profiles.clear();
        marketStatuses.clear();
    }
}
