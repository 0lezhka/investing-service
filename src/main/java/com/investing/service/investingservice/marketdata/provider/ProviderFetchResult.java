package com.investing.service.investingservice.marketdata.provider;

import com.investing.service.investingservice.marketdata.model.MarketDataRecord;

import java.util.List;

public record ProviderFetchResult(
        List<MarketDataRecord> instruments,
        List<String> unresolvedTickers,
        List<String> warnings
) {
}
