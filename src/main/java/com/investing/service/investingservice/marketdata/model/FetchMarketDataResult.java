package com.investing.service.investingservice.marketdata.model;

import java.time.Instant;
import java.util.List;

public record FetchMarketDataResult(
        List<MarketDataRecord> instruments,
        List<String> unresolvedTickers,
        List<String> warnings,
        Instant fetchedAt
) {
}
