package com.investing.service.investingservice.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketDataRecord(
        String requestedTicker,
        String resolvedSymbol,
        String name,
        MarketAssetType assetType,
        String exchange,
        String currency,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal changeAbsolute,
        BigDecimal changePercent,
        BigDecimal open,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        Instant marketTimestamp,
        MarketState marketState,
        Metadata metadata
) {
    public record Metadata(
            String source,
            boolean stale,
            boolean partial,
            List<String> fetchWarnings
    ) {
    }
}
