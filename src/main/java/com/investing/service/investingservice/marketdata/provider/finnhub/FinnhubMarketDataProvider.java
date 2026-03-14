package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.investing.service.investingservice.marketdata.model.MarketAssetType;
import com.investing.service.investingservice.marketdata.model.MarketDataField;
import com.investing.service.investingservice.marketdata.model.MarketDataRecord;
import com.investing.service.investingservice.marketdata.model.MarketState;
import com.investing.service.investingservice.marketdata.provider.MarketDataProvider;
import com.investing.service.investingservice.marketdata.provider.ProviderFetchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class FinnhubMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMarketDataProvider.class);
    private static final String SOURCE = "FINNHUB";

    private final WebClient webClient;
    private final FinnhubMarketDataProperties properties;
    private final FinnhubDataCache finnhubDataCache;

    public FinnhubMarketDataProvider(
            WebClient webClient,
            FinnhubMarketDataProperties properties,
            FinnhubDataCache finnhubDataCache
    ) {
        this.webClient = webClient;
        this.properties = properties;
        this.finnhubDataCache = finnhubDataCache;
    }

    @Override
    public ProviderFetchResult fetch(List<String> tickers, Set<MarketDataField> requestedFields) {
        log.info("Finnhub provider fetch started for {} ticker(s)", tickers.size());
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("Finnhub provider fetch skipped because API key is not configured");
            return new ProviderFetchResult(
                    List.of(),
                    List.copyOf(tickers),
                    List.of("Finnhub API key is not configured")
            );
        }

        Map<String, List<String>> warningsByTicker = new LinkedHashMap<>();
        Map<String, FinnhubQuoteResponse> quotesByTicker = loadCachedQuotes(tickers);
        Map<String, FinnhubProfileResponse> profilesByTicker = loadCachedProfiles(tickers);

        fetchMissingQuotes(tickers, quotesByTicker, warningsByTicker);
        fetchMissingProfiles(tickers, profilesByTicker, warningsByTicker);

        Map<String, MarketState> marketStateByExchange = loadMarketStates(profilesByTicker, warningsByTicker);

        List<MarketDataRecord> instruments = new ArrayList<>();
        List<String> unresolvedTickers = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();

        for (String ticker : tickers) {
            log.info("Resolving Finnhub data for ticker {}", ticker);
            List<String> tickerWarnings = warningsByTicker.computeIfAbsent(ticker, ignored -> new ArrayList<>());
            FinnhubQuoteResponse quote = quotesByTicker.get(ticker);
            FinnhubProfileResponse profile = profilesByTicker.get(ticker);

            if (!isUsableQuote(quote)) {
                if (hasProfileData(profile)) {
                    tickerWarnings.add("Finnhub returned profile data but no usable quote for ticker " + ticker);
                }
                unresolvedTickers.add(ticker);
                warnings.addAll(tickerWarnings);
                log.warn("Finnhub could not resolve ticker {}", ticker);
                continue;
            }

            boolean partial = !hasProfileData(profile);
            if (partial) {
                tickerWarnings.add("Finnhub profile data unavailable for ticker " + ticker);
                log.warn("Finnhub returned partial data for {}", ticker);
            }

            MarketState marketState = MarketState.UNKNOWN;
            if (hasProfileData(profile) && hasText(profile.exchange())) {
                marketState = marketStateByExchange.getOrDefault(profile.exchange(), MarketState.UNKNOWN);
            }

            MarketDataRecord record = new MarketDataRecord(
                    ticker,
                    hasText(profile == null ? null : profile.ticker()) ? profile.ticker() : ticker,
                    profile == null ? null : profile.name(),
                    MarketAssetType.UNKNOWN,
                    profile == null ? null : profile.exchange(),
                    profile == null ? null : profile.currency(),
                    quote.currentPrice(),
                    quote.previousClose(),
                    quote.changeAbsolute(),
                    quote.changePercent(),
                    quote.open(),
                    quote.dayHigh(),
                    quote.dayLow(),
                    Instant.ofEpochSecond(quote.timestamp()),
                    marketState,
                    new MarketDataRecord.Metadata(SOURCE, false, partial, List.copyOf(tickerWarnings))
            );

            instruments.add(record);
            warnings.addAll(tickerWarnings);
            log.info("Finnhub resolved ticker {}", ticker);
        }

        log.info(
                "Finnhub provider fetch completed with {} instrument(s), {} unresolved ticker(s), {} warning(s)",
                instruments.size(),
                unresolvedTickers.size(),
                warnings.size()
        );
        return new ProviderFetchResult(instruments, unresolvedTickers, List.copyOf(warnings));
    }

    private Map<String, FinnhubQuoteResponse> loadCachedQuotes(List<String> tickers) {
        Map<String, FinnhubQuoteResponse> quotesByTicker = new LinkedHashMap<>();
        for (String ticker : tickers) {
            finnhubDataCache.getQuote(ticker).ifPresent(quote -> {
                quotesByTicker.put(ticker, quote);
                log.debug("Finnhub quote cache hit for {}", ticker);
            });
        }
        return quotesByTicker;
    }

    private Map<String, FinnhubProfileResponse> loadCachedProfiles(List<String> tickers) {
        Map<String, FinnhubProfileResponse> profilesByTicker = new LinkedHashMap<>();
        for (String ticker : tickers) {
            finnhubDataCache.getProfile(ticker).ifPresent(profile -> {
                profilesByTicker.put(ticker, profile);
                log.debug("Finnhub profile cache hit for {}", ticker);
            });
        }
        return profilesByTicker;
    }

    private void fetchMissingQuotes(
            List<String> tickers,
            Map<String, FinnhubQuoteResponse> quotesByTicker,
            Map<String, List<String>> warningsByTicker
    ) {
        List<String> missingTickers = tickers.stream()
                .filter(ticker -> !quotesByTicker.containsKey(ticker))
                .toList();
        Map<String, EndpointFetchResult<FinnhubQuoteResponse>> results = fetchInBatches(missingTickers, this::fetchQuoteSafely);

        for (Map.Entry<String, EndpointFetchResult<FinnhubQuoteResponse>> entry : results.entrySet()) {
            String ticker = entry.getKey();
            EndpointFetchResult<FinnhubQuoteResponse> result = entry.getValue();
            if (result.warning() != null) {
                warningsByTicker.computeIfAbsent(ticker, ignored -> new ArrayList<>()).add(result.warning());
            }
            if (isUsableQuote(result.value())) {
                quotesByTicker.put(ticker, result.value());
                finnhubDataCache.putQuote(ticker, result.value());
            }
        }
    }

    private void fetchMissingProfiles(
            List<String> tickers,
            Map<String, FinnhubProfileResponse> profilesByTicker,
            Map<String, List<String>> warningsByTicker
    ) {
        List<String> missingTickers = tickers.stream()
                .filter(ticker -> !profilesByTicker.containsKey(ticker))
                .toList();
        Map<String, EndpointFetchResult<FinnhubProfileResponse>> results = fetchInBatches(missingTickers, this::fetchProfileSafely);

        for (Map.Entry<String, EndpointFetchResult<FinnhubProfileResponse>> entry : results.entrySet()) {
            String ticker = entry.getKey();
            EndpointFetchResult<FinnhubProfileResponse> result = entry.getValue();
            if (result.warning() != null) {
                warningsByTicker.computeIfAbsent(ticker, ignored -> new ArrayList<>()).add(result.warning());
            }
            if (result.value() != null) {
                profilesByTicker.put(ticker, result.value());
                if (hasProfileData(result.value())) {
                    finnhubDataCache.putProfile(ticker, result.value());
                }
            }
        }
    }

    private Map<String, MarketState> loadMarketStates(
            Map<String, FinnhubProfileResponse> profilesByTicker,
            Map<String, List<String>> warningsByTicker
    ) {
        Map<String, MarketState> marketStateByExchange = new LinkedHashMap<>();
        if (!properties.isEnableMarketStatus()) {
            return marketStateByExchange;
        }

        Map<String, List<String>> tickersByExchange = new LinkedHashMap<>();
        for (Map.Entry<String, FinnhubProfileResponse> entry : profilesByTicker.entrySet()) {
            FinnhubProfileResponse profile = entry.getValue();
            if (profile != null && hasText(profile.exchange())) {
                tickersByExchange.computeIfAbsent(profile.exchange(), ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }

        List<String> exchanges = new ArrayList<>(tickersByExchange.keySet());
        List<String> missingExchanges = new ArrayList<>();
        for (String exchange : exchanges) {
            if (marketStateByExchange.containsKey(exchange)) {
                continue;
            }
            FinnhubMarketStatusResponse response = finnhubDataCache.getMarketStatus(exchange).orElse(null);
            if (response != null) {
                MarketState marketState = toMarketState(response);
                if (marketState != MarketState.UNKNOWN) {
                    marketStateByExchange.put(exchange, marketState);
                }
            } else {
                missingExchanges.add(exchange);
            }
        }

        Map<String, EndpointFetchResult<FinnhubMarketStatusResponse>> fetchedStatuses =
                fetchInBatches(missingExchanges, this::fetchMarketStatusSafely);

        for (Map.Entry<String, EndpointFetchResult<FinnhubMarketStatusResponse>> entry : fetchedStatuses.entrySet()) {
            String exchange = entry.getKey();
            EndpointFetchResult<FinnhubMarketStatusResponse> result = entry.getValue();
            if (result.warning() != null) {
                for (String ticker : tickersByExchange.getOrDefault(exchange, List.of())) {
                    warningsByTicker.computeIfAbsent(ticker, ignored -> new ArrayList<>()).add(result.warning());
                }
            }

            MarketState marketState = toMarketState(result.value());
            if (marketState != MarketState.UNKNOWN) {
                marketStateByExchange.put(exchange, marketState);
                finnhubDataCache.putMarketStatus(exchange, result.value());
            }
        }

        return marketStateByExchange;
    }

    private EndpointFetchResult<FinnhubQuoteResponse> fetchQuoteSafely(String ticker) {
        try {
            log.debug("Requesting Finnhub quote for {}", ticker);
            return new EndpointFetchResult<>(fetchQuote(ticker), null);
        } catch (ProviderWarningException exception) {
            log.warn("Finnhub quote fetch failed for {}: {}", ticker, exception.getMessage());
            return new EndpointFetchResult<>(null, exception.getMessage());
        }
    }

    private EndpointFetchResult<FinnhubProfileResponse> fetchProfileSafely(String ticker) {
        try {
            log.debug("Requesting Finnhub profile for {}", ticker);
            return new EndpointFetchResult<>(fetchProfile(ticker), null);
        } catch (ProviderWarningException exception) {
            log.warn("Finnhub profile fetch failed for {}: {}", ticker, exception.getMessage());
            return new EndpointFetchResult<>(null, exception.getMessage());
        }
    }

    private EndpointFetchResult<FinnhubMarketStatusResponse> fetchMarketStatusSafely(String exchange) {
        try {
            log.debug("Requesting Finnhub market status for exchange {}", exchange);
            return new EndpointFetchResult<>(fetchMarketStatus(exchange), null);
        } catch (ProviderWarningException exception) {
            log.warn("Finnhub market status fetch failed for exchange {}: {}", exchange, exception.getMessage());
            return new EndpointFetchResult<>(null, exception.getMessage());
        }
    }

    private FinnhubQuoteResponse fetchQuote(String ticker) {
        return get("/quote", "symbol", ticker, FinnhubQuoteResponse.class, "quote", ticker).block();
    }

    private FinnhubProfileResponse fetchProfile(String ticker) {
        return get("/stock/profile2", "symbol", ticker, FinnhubProfileResponse.class, "profile", ticker).block();
    }

    private FinnhubMarketStatusResponse fetchMarketStatus(String exchange) {
        return get(
                "/stock/market-status",
                "exchange",
                exchange,
                FinnhubMarketStatusResponse.class,
                "market status",
                exchange
        ).block();
    }

    private <T> Map<String, EndpointFetchResult<T>> fetchInBatches(List<String> keys, Function<String, EndpointFetchResult<T>> loader) {
        Map<String, EndpointFetchResult<T>> results = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return results;
        }

        int batchSize = Math.max(1, properties.getCache().getBatchSize());
        int maxConcurrency = Math.max(1, Math.min(properties.getCache().getMaxConcurrency(), keys.size()));
        ExecutorService executorService = Executors.newFixedThreadPool(maxConcurrency);
        try {
            for (int start = 0; start < keys.size(); start += batchSize) {
                List<String> batch = keys.subList(start, Math.min(start + batchSize, keys.size()));
                List<CompletableFuture<Map.Entry<String, EndpointFetchResult<T>>>> futures = batch.stream()
                        .map(key -> CompletableFuture.supplyAsync(
                                () -> Map.entry(key, loader.apply(key)),
                                executorService
                        ))
                        .toList();

                for (CompletableFuture<Map.Entry<String, EndpointFetchResult<T>>> future : futures) {
                    Map.Entry<String, EndpointFetchResult<T>> entry = future.join();
                    results.put(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            executorService.shutdown();
        }

        return results;
    }

    private <T> Mono<T> get(
            String path,
            String queryParameter,
            String queryValue,
            Class<T> bodyType,
            String endpointName,
            String reference
    ) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam(queryParameter, queryValue)
                        .queryParam("token", properties.getApiKey())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(toWarningException(endpointName, reference, response.statusCode().value()))))
                .bodyToMono(bodyType)
                .onErrorMap(WebClientResponseException.class, exception ->
                        toWarningException(endpointName, reference, exception.getStatusCode().value()))
                .onErrorMap(
                        throwable -> !(throwable instanceof ProviderWarningException),
                        throwable -> new ProviderWarningException(
                                "Finnhub " + endpointName + " request failed for " + reference + ": " + throwable.getMessage()
                        )
                );
    }

    private ProviderWarningException toWarningException(String endpointName, String reference, int statusCode) {
        if (statusCode == 429) {
            return new ProviderWarningException("Finnhub rate limit hit for " + endpointName + " request on " + reference);
        }
        if (statusCode >= 500) {
            return new ProviderWarningException("Finnhub temporary failure on " + endpointName + " request for " + reference);
        }
        return new ProviderWarningException("Finnhub " + endpointName + " request failed for " + reference + " with HTTP " + statusCode);
    }

    private MarketState toMarketState(FinnhubMarketStatusResponse response) {
        if (response == null || response.isOpen() == null) {
            return MarketState.UNKNOWN;
        }
        return response.isOpen() ? MarketState.OPEN : MarketState.CLOSED;
    }

    private boolean isUsableQuote(FinnhubQuoteResponse quote) {
        return quote != null
                && quote.timestamp() != null
                && quote.timestamp() > 0
                && quote.currentPrice() != null;
    }

    private boolean hasProfileData(FinnhubProfileResponse profile) {
        return profile != null
                && StreamSupport.anyMatch(
                hasText(profile.ticker()),
                hasText(profile.name()),
                hasText(profile.exchange()),
                hasText(profile.currency())
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record EndpointFetchResult<T>(T value, String warning) {
    }

    private static final class ProviderWarningException extends RuntimeException {
        private ProviderWarningException(String message) {
            super(message);
        }
    }

    private static final class StreamSupport {
        private static boolean anyMatch(boolean... values) {
            for (boolean value : values) {
                if (value) {
                    return true;
                }
            }
            return false;
        }
    }
}
