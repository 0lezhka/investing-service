package com.investing.service.investingservice.marketdata.provider.finnhub;

import java.util.Optional;

public interface FinnhubDataCache {

    Optional<FinnhubQuoteResponse> getQuote(String ticker);

    void putQuote(String ticker, FinnhubQuoteResponse quote);

    Optional<FinnhubProfileResponse> getProfile(String ticker);

    void putProfile(String ticker, FinnhubProfileResponse profile);

    Optional<FinnhubMarketStatusResponse> getMarketStatus(String exchange);

    void putMarketStatus(String exchange, FinnhubMarketStatusResponse marketStatus);
}
