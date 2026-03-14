package com.investing.service.investingservice.rebalancing.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataQuery;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataResult;
import com.investing.service.investingservice.marketdata.model.MarketDataRecord;
import com.investing.service.investingservice.marketdata.model.MarketState;
import com.investing.service.investingservice.marketdata.provider.ProviderFetchResult;
import com.investing.service.investingservice.marketdata.service.FetchMarketDataUseCase;
import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import com.investing.service.investingservice.rebalancing.service.FeeModel;
import com.investing.service.investingservice.rebalancing.service.RebalanceEngine;
import com.investing.service.investingservice.rebalancing.service.RebalanceService;
import com.investing.service.investingservice.rebalancing.service.SizingModel;
import com.investing.service.investingservice.rebalancing.service.WeightsService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebalanceCliRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RebalanceService rebalanceService = new RebalanceService(
            new RebalanceEngine(new WeightsService(), new FeeModel(), new SizingModel())
    );

    @Test
    void runReturnsOkAndPrintsSummary() throws Exception {
        Path input = Files.createTempFile("rebalance-cli", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("REBALANCE SUMMARY | Status: OK"));
        assertTrue(output.contains("[Execution Context]"));
        assertTrue(output.contains("[Portfolio And Plan]"));
        assertTrue(output.contains("[Metrics Before Vs After]"));
        assertTrue(output.contains("| Cash Gap |"));
        assertFalse(output.contains("Exact Cash Gap"));
        assertTrue(output.contains("Additional Cash Exact"));
        assertTrue(output.contains("[Per-Ticker Before Vs After]"));
        assertTrue(output.contains("Ticker"));
        assertTrue(output.contains("Target"));
        assertTrue(output.contains("Target %"));
        assertTrue(output.contains("Value Before"));
        assertTrue(output.contains("Value After"));
        assertTrue(output.contains("% Delta"));
        assertTrue(output.contains("| VOO    | VOO"));
        assertTrue(output.contains("50%"));
        assertTrue(output.contains("| BND    | BND"));
        assertTrue(output.contains("[Recommended Trades]"));
        assertTrue(output.contains("| Step | Side | Asset | Quantity | Notional | Fee | Composite |"));
    }

    @Test
    void runSummaryShowsTickerLevelAllocationChangesForCompositeRoutes() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-composite", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleCompositeBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertTrue(exitCode == RebalanceCliExitCode.OK || exitCode == RebalanceCliExitCode.PARTIAL);
        String output = stdout.toString(StandardCharsets.UTF_8);
        String allocationSection = output.substring(
                output.indexOf("[Per-Ticker Before Vs After]"),
                output.indexOf("[Recommended Trades]")
        );
        assertTrue(output.contains("[Per-Ticker Before Vs After]"));
        assertTrue(allocationSection.contains("| VXUS   | INTL"));
        assertTrue(allocationSection.contains("30%"));
        assertTrue(allocationSection.contains("| 0"));
        assertTrue(allocationSection.contains("| 100"));
        assertTrue(allocationSection.contains("11.1111%"));
        assertTrue(allocationSection.contains("| AAA    | INTL"));
    }

    @Test
    void runSummaryMarksIgnoredTickersAndExcludesThemFromPercentages() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-ignored", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleIgnoredTickerCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertTrue(exitCode == RebalanceCliExitCode.OK || exitCode == RebalanceCliExitCode.PARTIAL);
        String output = stdout.toString(StandardCharsets.UTF_8);
        String allocationSection = output.substring(
                output.indexOf("[Per-Ticker Before Vs After]"),
                output.indexOf("[Recommended Trades]")
        );
        assertTrue(allocationSection.contains("| IBKR   | ignored | n/a"));
        assertTrue(allocationSection.contains("| VOO    | VOO"));
        assertTrue(allocationSection.contains("71.4286%"));
        assertFalse(allocationSection.contains("62.5%"));
    }

    @Test
    void validateReturnsInvalidInput() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-invalid", ".json");
        Files.writeString(input, """
                {
                  "portfolio": {
                    "cash": { "amount": 0, "currency": "USD" },
                    "positions": [
                      { "assetId": "VOO", "quantity": -1, "price": 100 }
                    ]
                  }
                }
                """);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.ValidateCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY
        ));

        assertEquals(RebalanceCliExitCode.INVALID_INPUT, exitCode);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("VALIDATION SUMMARY | Status: FAILED"));
        assertTrue(output.contains("[Request Context]"));
        assertTrue(output.contains("Reason               : Negative quantities are not allowed"));
    }

    @Test
    void validateReturnsOkSummaryWithRequestContext() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-valid", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.ValidateCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("VALIDATION SUMMARY | Status: OK"));
        assertTrue(output.contains("Policy            : BUY_ONLY"));
        assertTrue(output.contains("Positions         : 2"));
        assertTrue(output.contains("Reason               : input accepted"));
    }

    @Test
    void templateProducesJson() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.TemplateCommand(
                RebalanceCommand.Policy.BUY_ONLY_PLAN_N,
                true,
                true
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"policy\""));
        assertTrue(output.contains("\"composites\""));
    }

    @Test
    void applyOverridesCanCreateMissingPlanAndThreshold() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-overrides", ".json");
        RebalanceCommand command = new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        List.of(
                                new RebalanceCommand.Position("VOO", "VOO", "USD", new BigDecimal("5"), new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("BND", "BND", "USD", BigDecimal.ZERO, new BigDecimal("100"), true, true, true, List.of())
                        )
                ),
                List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.5")),
                        new RebalanceCommand.TargetAllocation("BND", new BigDecimal("0.5"))
                ),
                List.of(),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, new BigDecimal("100"), "USD"),
                null,
                null,
                new RebalanceCommand.Fees(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                null
        );
        Files.writeString(input, objectMapper.writeValueAsString(command));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.JSON,
                false,
                false,
                new BigDecimal("400"),
                RebalanceCommand.Policy.BUY_ONLY_PLAN_N,
                4,
                RebalanceCommand.ThresholdType.MAX_ABS,
                new BigDecimal("0.02"),
                false,
                false
        ));

        assertTrue(exitCode == RebalanceCliExitCode.OK || exitCode == RebalanceCliExitCode.PARTIAL);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"policy\":\"BUY_ONLY_PLAN_N\""));
        assertTrue(output.contains("\"status\":\"OK\"") || output.contains("\"status\":\"PARTIAL\""));
    }

    @Test
    void runWithLoadCurrentPricesRefreshesPortfolioPricesBeforeRebalancing() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-live-prices", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger marketDataCalls = new AtomicInteger();
        AtomicReference<RebalanceCommand> capturedCommand = new AtomicReference<>();
        FetchMarketDataUseCase fetchMarketDataUseCase = fakeUseCase(query -> {
            marketDataCalls.incrementAndGet();
            assertEquals(List.of("VOO", "BND"), query.tickers());
            return new FetchMarketDataResult(
                    List.of(
                            marketRecord("VOO", "VOO", "110"),
                            marketRecord("BND", "BND", "90")
                    ),
                    List.of(),
                    List.of(),
                    Instant.parse("2026-03-13T20:00:00Z")
            );
        });
        RebalanceCliRunner runner = new RebalanceCliRunner(
                capturingRebalanceService(capturedCommand),
                fetchMarketDataUseCase,
                objectMapper,
                new RebalanceCliFormatter(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.JSON,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        assertEquals(1, marketDataCalls.get());
        assertEquals(new BigDecimal("110"), capturedCommand.get().portfolio().positions().get(0).price());
        assertEquals(new BigDecimal("90"), capturedCommand.get().portfolio().positions().get(1).price());
    }

    @Test
    void runWithLoadCurrentPricesFailsWhenAnyTickerIsUnresolved() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-live-prices-fail", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger marketDataCalls = new AtomicInteger();
        AtomicInteger rebalanceCalls = new AtomicInteger();
        FetchMarketDataUseCase fetchMarketDataUseCase = fakeUseCase(query -> {
            marketDataCalls.incrementAndGet();
            return new FetchMarketDataResult(
                    List.of(marketRecord("VOO", "VOO", "110")),
                    List.of("BND"),
                    List.of("BND missing"),
                    Instant.parse("2026-03-13T20:00:00Z")
            );
        });
        RebalanceCliRunner runner = new RebalanceCliRunner(
                countingRebalanceService(rebalanceCalls),
                fetchMarketDataUseCase,
                objectMapper,
                new RebalanceCliFormatter(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.JSON,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.USAGE_ERROR, exitCode);
        assertEquals(1, marketDataCalls.get());
        assertEquals(0, rebalanceCalls.get());
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unable to load current prices for: BND"));
    }

    @Test
    void runWithLoadCurrentPricesSkipsIgnoredAssets() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-live-prices-ignored", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleIgnoredTickerCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger marketDataCalls = new AtomicInteger();
        AtomicReference<RebalanceCommand> capturedCommand = new AtomicReference<>();
        FetchMarketDataUseCase fetchMarketDataUseCase = fakeUseCase(query -> {
            marketDataCalls.incrementAndGet();
            assertEquals(List.of("VOO", "BND"), query.tickers());
            return new FetchMarketDataResult(
                    List.of(
                            marketRecord("VOO", "VOO", "110"),
                            marketRecord("BND", "BND", "90")
                    ),
                    List.of(),
                    List.of(),
                    Instant.parse("2026-03-13T20:00:00Z")
            );
        });
        RebalanceCliRunner runner = new RebalanceCliRunner(
                capturingRebalanceService(capturedCommand),
                fetchMarketDataUseCase,
                objectMapper,
                new RebalanceCliFormatter(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.JSON,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        assertEquals(1, marketDataCalls.get());
        assertEquals(new BigDecimal("110"), capturedCommand.get().portfolio().positions().get(0).price());
        assertEquals(new BigDecimal("90"), capturedCommand.get().portfolio().positions().get(1).price());
        assertEquals(new BigDecimal("50"), capturedCommand.get().portfolio().positions().get(2).price());
    }

    @Test
    void runWithoutLoadCurrentPricesDoesNotCallMarketData() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-no-live-prices", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleBuyOnlyCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger marketDataCalls = new AtomicInteger();
        FetchMarketDataUseCase fetchMarketDataUseCase = fakeUseCase(query -> {
            marketDataCalls.incrementAndGet();
            return new FetchMarketDataResult(List.of(), List.of(), List.of(), Instant.parse("2026-03-13T20:00:00Z"));
        });
        RebalanceCliRunner runner = newRunner(stdout, stderr, fetchMarketDataUseCase);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.OK, exitCode);
        assertEquals(0, marketDataCalls.get());
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("REBALANCE SUMMARY | Status: OK"));
        assertTrue(output.contains("Budget            : NEW_CASH_ONLY 200 USD"));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("CLI error"));
    }

    @Test
    void runSummaryShowsNoTradesReasonClearly() throws Exception {
        Path input = Files.createTempFile("rebalance-cli-no-trades", ".json");
        Files.writeString(input, objectMapper.writeValueAsString(sampleNoTradesCommand()));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        RebalanceCliRunner runner = newRunner(stdout, stderr);

        int exitCode = runner.run(new RebalanceCliCommand.RunCommand(
                input.toString(),
                RebalanceCliCommand.OutputFormat.SUMMARY,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false
        ));

        assertEquals(RebalanceCliExitCode.NO_TRADES, exitCode);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("REBALANCE SUMMARY | Status: NO_TRADES"));
        assertTrue(output.contains("Reason: Portfolio is already within threshold"));
        assertTrue(output.contains("No trades recommended"));
        assertTrue(output.contains("[Warnings And Notes]"));
    }

    private RebalanceCliRunner newRunner(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        return newRunner(stdout, stderr, fakeUseCase(query ->
                new FetchMarketDataResult(List.of(), List.of(), List.of(), Instant.parse("2026-03-13T20:00:00Z"))
        ));
    }

    private RebalanceCliRunner newRunner(
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr,
            FetchMarketDataUseCase fetchMarketDataUseCase
    ) {
        return new RebalanceCliRunner(
                rebalanceService,
                fetchMarketDataUseCase,
                objectMapper,
                new RebalanceCliFormatter(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
    }

    private RebalanceCommand sampleBuyOnlyCommand() {
        return new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        List.of(
                                new RebalanceCommand.Position("VOO", "VOO", "USD", new BigDecimal("5"), new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("BND", "BND", "USD", BigDecimal.ZERO, new BigDecimal("100"), true, true, true, List.of())
                        )
                ),
                List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.5")),
                        new RebalanceCommand.TargetAllocation("BND", new BigDecimal("0.5"))
                ),
                List.of(),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, new BigDecimal("200"), "USD"),
                null,
                new RebalanceCommand.Threshold(RebalanceCommand.ThresholdType.MAX_ABS, new BigDecimal("0.000001")),
                new RebalanceCommand.Fees(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                new RebalanceCommand.Options(
                        new BigDecimal("0.000001"),
                        null,
                        RebalanceCommand.RoundingMode.FLOOR,
                        false,
                        false,
                        false,
                        null,
                        List.of()
                )
        );
    }

    private RebalanceCommand sampleNoTradesCommand() {
        return new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        List.of(
                                new RebalanceCommand.Position("VOO", "VOO", "USD", new BigDecimal("6"), new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("BND", "BND", "USD", new BigDecimal("4"), new BigDecimal("100"), true, true, true, List.of())
                        )
                ),
                List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.6")),
                        new RebalanceCommand.TargetAllocation("BND", new BigDecimal("0.4"))
                ),
                List.of(),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, BigDecimal.ZERO, "USD"),
                null,
                new RebalanceCommand.Threshold(RebalanceCommand.ThresholdType.MAX_ABS, new BigDecimal("0.01")),
                new RebalanceCommand.Fees(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                new RebalanceCommand.Options(
                        new BigDecimal("0.000001"),
                        null,
                        RebalanceCommand.RoundingMode.FLOOR,
                        false,
                        false,
                        false,
                        null,
                        List.of()
                )
        );
    }

    private RebalanceCommand sampleCompositeBuyOnlyCommand() {
        return new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        List.of(
                                new RebalanceCommand.Position("VOO", "VOO", "USD", new BigDecimal("7"), new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("AAA", "AAA", "USD", new BigDecimal("1"), new BigDecimal("100"), true, false, false, List.of()),
                                new RebalanceCommand.Position("VXUS", "VXUS", "USD", BigDecimal.ZERO, new BigDecimal("100"), true, true, true, List.of())
                        )
                ),
                List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.70"))
                ),
                List.of(new RebalanceCommand.Composite(
                        "INTL",
                        new BigDecimal("0.30"),
                        List.of("AAA", "VXUS"),
                        new RebalanceCommand.TradePolicy(List.of("VXUS"), List.of(), List.of("AAA"))
                )),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, new BigDecimal("100"), "USD"),
                null,
                new RebalanceCommand.Threshold(RebalanceCommand.ThresholdType.MAX_ABS, new BigDecimal("0.000001")),
                new RebalanceCommand.Fees(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                new RebalanceCommand.Options(
                        new BigDecimal("0.000001"),
                        null,
                        RebalanceCommand.RoundingMode.FLOOR,
                        false,
                        false,
                        false,
                        null,
                        List.of()
                )
        );
    }

    private RebalanceCommand sampleIgnoredTickerCommand() {
        return new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        List.of(
                                new RebalanceCommand.Position("VOO", "VOO", "USD", new BigDecimal("5"), new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("BND", "BND", "USD", BigDecimal.ZERO, new BigDecimal("100"), true, true, true, List.of()),
                                new RebalanceCommand.Position("IBKR", "IBKR", "USD", new BigDecimal("1"), new BigDecimal("50"), true, true, true, List.of())
                        )
                ),
                List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.5")),
                        new RebalanceCommand.TargetAllocation("BND", new BigDecimal("0.5"))
                ),
                List.of(),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, new BigDecimal("200"), "USD"),
                null,
                new RebalanceCommand.Threshold(RebalanceCommand.ThresholdType.MAX_ABS, new BigDecimal("0.000001")),
                new RebalanceCommand.Fees(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                new RebalanceCommand.Options(
                        new BigDecimal("0.000001"),
                        null,
                        RebalanceCommand.RoundingMode.FLOOR,
                        false,
                        false,
                        false,
                        null,
                        List.of("IBKR")
                )
        );
    }

    private FetchMarketDataUseCase fakeUseCase(FetchFunction fetchFunction) {
        return new FetchMarketDataUseCase((tickers, requestedFields) ->
                toProviderResult(fetchFunction.fetch(new FetchMarketDataQuery(
                        tickers,
                        requestedFields == null ? Set.of() : requestedFields,
                        false
                ))),
                Clock.fixed(Instant.parse("2026-03-13T20:00:00Z"), ZoneOffset.UTC)
        );
    }

    private ProviderFetchResult toProviderResult(FetchMarketDataResult result) {
        return new ProviderFetchResult(
                result.instruments(),
                result.unresolvedTickers(),
                result.warnings()
        );
    }

    private MarketDataRecord marketRecord(String requestedTicker, String resolvedSymbol, String currentPrice) {
        return new MarketDataRecord(
                requestedTicker,
                resolvedSymbol,
                resolvedSymbol,
                null,
                "US",
                "USD",
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                Instant.parse("2026-03-13T20:00:00Z"),
                MarketState.UNKNOWN,
                new MarketDataRecord.Metadata("TEST", false, false, List.of())
        );
    }

    private RebalanceService capturingRebalanceService(AtomicReference<RebalanceCommand> capturedCommand) {
        return new RebalanceService(new RebalanceEngine(new WeightsService(), new FeeModel(), new SizingModel())) {
            @Override
            public RebalanceResult rebalance(RebalanceCommand command) {
                capturedCommand.set(command);
                return super.rebalance(command);
            }
        };
    }

    private RebalanceService countingRebalanceService(AtomicInteger rebalanceCalls) {
        return new RebalanceService(new RebalanceEngine(new WeightsService(), new FeeModel(), new SizingModel())) {
            @Override
            public RebalanceResult rebalance(RebalanceCommand command) {
                rebalanceCalls.incrementAndGet();
                return super.rebalance(command);
            }
        };
    }

    @FunctionalInterface
    private interface FetchFunction {
        FetchMarketDataResult fetch(FetchMarketDataQuery query);
    }
}
