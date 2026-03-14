package com.investing.service.investingservice.marketdata.service;

import com.investing.service.investingservice.marketdata.model.FetchMarketDataQuery;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataResult;
import com.investing.service.investingservice.marketdata.model.MarketDataField;
import com.investing.service.investingservice.marketdata.provider.MarketDataProvider;
import com.investing.service.investingservice.marketdata.provider.ProviderFetchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FetchMarketDataUseCase {

    private static final Logger log = LoggerFactory.getLogger(FetchMarketDataUseCase.class);

    private static final Set<MarketDataField> DEFAULT_FIELDS = Set.of(MarketDataField.BASIC_SNAPSHOT);

    private final MarketDataProvider marketDataProvider;
    private final Clock clock;

    public FetchMarketDataUseCase(MarketDataProvider marketDataProvider, Clock clock) {
        this.marketDataProvider = marketDataProvider;
        this.clock = clock;
    }

    public FetchMarketDataResult fetch(FetchMarketDataQuery query) {
        List<String> normalizedTickers = normalizeTickers(query);
        Set<MarketDataField> requestedFields = normalizeRequestedFields(query.requestedFields());
        log.info("Fetching market data for {} ticker(s): {}", normalizedTickers.size(), normalizedTickers);
        log.debug("Requested market data fields: {}", requestedFields);

        ProviderFetchResult providerResult = marketDataProvider.fetch(normalizedTickers, requestedFields);
        Instant fetchedAt = Instant.now(clock);
        log.info(
                "Market data fetch completed with {} instrument(s), {} unresolved ticker(s), {} warning(s)",
                providerResult.instruments().size(),
                providerResult.unresolvedTickers().size(),
                providerResult.warnings().size()
        );

        if (query.failOnAllMissing() && providerResult.instruments().isEmpty()) {
            log.warn("Strict market data fetch failed because no tickers were resolved");
            throw new AllTickersUnresolvedException(
                    "Unable to resolve any of the requested tickers: " + String.join(", ", normalizedTickers)
            );
        }

        return new FetchMarketDataResult(
                List.copyOf(providerResult.instruments()),
                List.copyOf(providerResult.unresolvedTickers()),
                List.copyOf(providerResult.warnings()),
                fetchedAt
        );
    }

    private List<String> normalizeTickers(FetchMarketDataQuery query) {
        if (query == null || query.tickers() == null || query.tickers().isEmpty()) {
            throw new IllegalArgumentException("At least one ticker is required");
        }

        List<String> normalized = query.tickers().stream()
                .map(ticker -> ticker == null ? null : ticker.trim())
                .filter(ticker -> ticker != null && !ticker.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .toList();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one ticker is required");
        }

        return normalized;
    }

    private Set<MarketDataField> normalizeRequestedFields(Set<MarketDataField> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return DEFAULT_FIELDS;
        }
        return Set.copyOf(new LinkedHashSet<>(requestedFields));
    }
}
