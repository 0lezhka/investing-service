package com.investing.service.investingservice.marketdata.service;

import com.investing.service.investingservice.testsupport.marketdata.FetchMarketDataTestConfig;
import com.investing.service.investingservice.testsupport.marketdata.InMemoryFinnhubDataCache;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataQuery;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataResult;
import com.investing.service.investingservice.marketdata.model.MarketDataRecord;
import com.investing.service.investingservice.marketdata.model.MarketState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(FetchMarketDataTestConfig.class)
class FetchMarketDataUseCaseIT {

    @Autowired
    private FetchMarketDataUseCase fetchMarketDataUseCase;

    @Autowired
    private MockWebServer mockWebServer;

    @Autowired
    private InMemoryFinnhubDataCache finnhubDataCache;

    @BeforeEach
    void resetState() throws InterruptedException {
        finnhubDataCache.clear();
        mockWebServer.setDispatcher(new QueueDispatcher());
        while (mockWebServer.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // Drain any leftover requests because the Spring test context is reused between methods.
        }
    }

    @Test
    void returnsMappedSnapshotFromFinnhub() {
        mockWebServer.enqueue(jsonResponse("""
                {"c":201.15,"pc":198.00,"d":3.15,"dp":1.59,"o":199.10,"h":202.00,"l":198.50,"t":1710000000}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"AAPL","name":"Apple Inc.","exchange":"US","currency":"USD"}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"isOpen":true}
                """));

        FetchMarketDataResult result = fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(List.of("aapl"), Set.of(), false));

        assertEquals(List.of(), result.unresolvedTickers());
        assertEquals(List.of(), result.warnings());
        assertEquals(Instant.parse("2026-03-13T20:00:00Z"), result.fetchedAt());

        MarketDataRecord record = result.instruments().getFirst();
        assertEquals("AAPL", record.requestedTicker());
        assertEquals("AAPL", record.resolvedSymbol());
        assertEquals("Apple Inc.", record.name());
        assertEquals("US", record.exchange());
        assertEquals("USD", record.currency());
        assertEquals(MarketState.OPEN, record.marketState());
        assertEquals(Instant.ofEpochSecond(1710000000), record.marketTimestamp());
        assertBigDecimal("201.15", record.currentPrice());
        assertBigDecimal("198.00", record.previousClose());
        assertBigDecimal("3.15", record.changeAbsolute());
        assertBigDecimal("1.59", record.changePercent());
        assertFalse(record.metadata().partial());
        assertEquals("FINNHUB", record.metadata().source());
        assertEquals(List.of(), record.metadata().fetchWarnings());
    }

    @Test
    void reusesCachedResponsesForRepeatedTickerFetch() throws Exception {
        enqueueSnapshot("AAPL", "Apple Inc.", "US", "USD", true, 1710000000L, "201.15");

        FetchMarketDataQuery query = new FetchMarketDataQuery(List.of("AAPL"), Set.of(), false);
        fetchMarketDataUseCase.fetch(query);
        fetchMarketDataUseCase.fetch(query);

        List<RecordedRequest> requests = drainRequests(3);
        assertEquals(3, requests.size());
        assertEquals(1, countRequestsContaining(requests, "/quote"));
        assertEquals(1, countRequestsContaining(requests, "/stock/profile2"));
        assertEquals(1, countRequestsContaining(requests, "/stock/market-status"));
    }

    @Test
    void fetchesOnlyUncachedTickersInMixedRequest() throws Exception {
        enqueueSnapshot("AAPL", "Apple Inc.", "US", "USD", true, 1710000000L, "201.15");
        fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(List.of("AAPL"), Set.of(), false));

        mockWebServer.enqueue(jsonResponse("""
                {"c":410.44,"pc":405.00,"d":5.44,"dp":1.34,"o":406.10,"h":411.00,"l":404.50,"t":1710000001}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"MSFT","name":"Microsoft Corp.","exchange":"US","currency":"USD"}
                """));

        FetchMarketDataResult result = fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(List.of("AAPL", "MSFT"), Set.of(), false));

        assertEquals(2, result.instruments().size());
        List<RecordedRequest> requests = drainRequests(5);
        assertEquals(5, requests.size());
        assertEquals(2, countRequestsContaining(requests, "/quote"));
        assertEquals(2, countRequestsContaining(requests, "/stock/profile2"));
        assertEquals(1, countRequestsContaining(requests, "/stock/market-status"));
    }

    @Test
    void reusesMarketStatusAcrossTickersSharingExchange() throws Exception {
        mockWebServer.enqueue(jsonResponse("""
                {"c":201.15,"pc":198.00,"d":3.15,"dp":1.59,"o":199.10,"h":202.00,"l":198.50,"t":1710000000}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"c":410.44,"pc":405.00,"d":5.44,"dp":1.34,"o":406.10,"h":411.00,"l":404.50,"t":1710000001}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"AAPL","name":"Apple Inc.","exchange":"US","currency":"USD"}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"MSFT","name":"Microsoft Corp.","exchange":"US","currency":"USD"}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"isOpen":true}
                """));

        FetchMarketDataResult result = fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(List.of("AAPL", "MSFT"), Set.of(), false));

        assertEquals(2, result.instruments().size());
        List<RecordedRequest> requests = drainRequests(5);
        assertEquals(5, requests.size());
        assertEquals(1, countRequestsContaining(requests, "/stock/market-status"));
    }

    @Test
    void returnsPartialSuccessWhenSomeTickersFailOrHaveIncompleteData() {
        mockWebServer.enqueue(jsonResponse("""
                {"c":201.15,"pc":198.00,"d":3.15,"dp":1.59,"o":199.10,"h":202.00,"l":198.50,"t":1710000000}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"c":410.44,"pc":405.00,"d":5.44,"dp":1.34,"o":406.10,"h":411.00,"l":404.50,"t":1710000001}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"c":0,"pc":0,"d":0,"dp":0,"o":0,"h":0,"l":0,"t":0}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"AAPL","name":"Apple Inc.","exchange":"US","currency":"USD"}
                """));
        mockWebServer.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"not found\"}"));
        mockWebServer.enqueue(jsonResponse("""
                {}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {"isOpen":false}
                """));

        FetchMarketDataResult result = fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(
                List.of("AAPL", "MSFT", "BAD"),
                null,
                false
        ));

        assertEquals(List.of("BAD"), result.unresolvedTickers());
        assertEquals(2, result.instruments().size());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("profile request failed for MSFT with HTTP 404")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("profile data unavailable for ticker MSFT")));

        MarketDataRecord partial = result.instruments().stream()
                .filter(record -> "MSFT".equals(record.requestedTicker()))
                .findFirst()
                .orElseThrow();

        assertTrue(partial.metadata().partial());
        assertEquals(MarketState.UNKNOWN, partial.marketState());
        assertTrue(partial.metadata().fetchWarnings().stream().anyMatch(warning -> warning.contains("MSFT")));
    }

    @Test
    void throwsWhenStrictModeIsEnabledAndNothingResolves() {
        mockWebServer.enqueue(jsonResponse("""
                {"c":0,"pc":0,"d":0,"dp":0,"o":0,"h":0,"l":0,"t":0}
                """));
        mockWebServer.enqueue(jsonResponse("""
                {}
                """));

        RuntimeException exception = assertThrows(AllTickersUnresolvedException.class, () ->
                fetchMarketDataUseCase.fetch(new FetchMarketDataQuery(List.of("BAD"), null, true))
        );

        assertEquals("Unable to resolve any of the requested tickers: BAD", exception.getMessage());
        assertInstanceOf(AllTickersUnresolvedException.class, exception);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private void enqueueSnapshot(
            String ticker,
            String name,
            String exchange,
            String currency,
            boolean open,
            long timestamp,
            String currentPrice
    ) {
        mockWebServer.enqueue(jsonResponse("""
                {"c":%s,"pc":198.00,"d":3.15,"dp":1.59,"o":199.10,"h":202.00,"l":198.50,"t":%d}
                """.formatted(currentPrice, timestamp)));
        mockWebServer.enqueue(jsonResponse("""
                {"ticker":"%s","name":"%s","exchange":"%s","currency":"%s"}
                """.formatted(ticker, name, exchange, currency)));
        mockWebServer.enqueue(jsonResponse("""
                {"isOpen":%s}
                """.formatted(open)));
    }

    private List<RecordedRequest> drainRequests(int expectedCount) throws InterruptedException {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
            if (request != null) {
                requests.add(request);
            }
        }
        return requests;
    }

    private long countRequestsContaining(List<RecordedRequest> requests, String pathFragment) {
        return requests.stream()
                .map(RecordedRequest::getPath)
                .filter(path -> path != null && path.contains(pathFragment))
                .count();
    }

    private static void assertBigDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
