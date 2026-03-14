package com.investing.service.investingservice.marketdata.model;

import java.util.List;
import java.util.Set;

public record FetchMarketDataQuery(
        List<String> tickers,
        Set<MarketDataField> requestedFields,
        boolean failOnAllMissing
) {
}
