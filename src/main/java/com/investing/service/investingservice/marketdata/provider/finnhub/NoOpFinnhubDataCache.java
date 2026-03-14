package com.investing.service.investingservice.marketdata.provider.finnhub;

import java.util.Optional;

public class NoOpFinnhubDataCache implements FinnhubDataCache {

    @Override
    public Optional<FinnhubQuoteResponse> getQuote(String ticker) {
        return Optional.empty();
    }

    @Override
    public void putQuote(String ticker, FinnhubQuoteResponse quote) {
    }

    @Override
    public Optional<FinnhubProfileResponse> getProfile(String ticker) {
        return Optional.empty();
    }

    @Override
    public void putProfile(String ticker, FinnhubProfileResponse profile) {
    }

    @Override
    public Optional<FinnhubMarketStatusResponse> getMarketStatus(String exchange) {
        return Optional.empty();
    }

    @Override
    public void putMarketStatus(String exchange, FinnhubMarketStatusResponse marketStatus) {
    }
}
