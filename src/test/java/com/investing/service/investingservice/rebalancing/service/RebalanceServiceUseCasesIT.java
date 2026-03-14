package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.cli.RebalanceCliFormatter;
import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(RebalanceServiceUseCasesIT.TestConfig.class)
class RebalanceServiceUseCasesIT {

    @Autowired
    private RebalanceService rebalanceService;

    /**
     * <pre>
     * VOO 60% + BND 40%
     * target stays the same
     * ->
     * no trades because the portfolio is already inside the threshold
     * </pre>
     */
    @Test
    void returnsNoTradesWhenPortfolioIsAlreadyWithinThreshold() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "6", "100"), position("BND", "4", "100")),
                List.of(target("VOO", "0.60"), target("BND", "0.40")),
                List.of(),
                null,
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(RebalanceResult.Status.NO_TRADES, result.status());
        assertTrue(result.recommended().trades().isEmpty());
        assertEquals(result.metrics().before(), result.metrics().after());
        assertEquals(result.metrics().targetThresholdCashGapBefore(), result.metrics().targetThresholdCashGapAfter());
        assertEquals(0, result.metrics().targetThresholdCashGapBefore().compareTo(BigDecimal.ZERO));
        assertEquals(result.metrics().exactTargetCashGapBefore(), result.metrics().exactTargetCashGapAfter());
    }

    /**
     * <pre>
     * any portfolio
     * + kill switch enabled
     * ->
     * engine stops immediately with NO_TRADES
     * </pre>
     */
    @Test
    void returnsNoTradesWhenKillSwitchIsEnabled() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.CLASSIC_FULL_REBALANCE,
                portfolio(cash("0"), position("VOO", "9", "100"), position("BND", "1", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                null,
                null,
                null,
                noFees(),
                standardSizing(),
                constraints(null, null, risk(null, true)),
                null
        ));

        assertEquals(RebalanceResult.Status.NO_TRADES, result.status());
        assertEquals("Kill switch enabled", result.reason());
        assertTrue(result.metrics().bindingConstraints().contains("KILL_SWITCH"));
    }

    /**
     * <pre>
     * start: only VOO is held
     * budget: 200 USD
     * target: buy VOO/BND to 50/50
     * ->
     * only BUY trades are created and total spend stays inside budget
     * </pre>
     */
    @Test
    void executesSimpleBuyOnlyRebalanceWithinBudget() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "8", "100"), position("BND", "0", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("200"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                fees("1.00", "0.0000", "1.00"),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        BigDecimal totalSpent = sumSpent(result.recommended().trades());

        assertTrue(result.status() == RebalanceResult.Status.OK || result.status() == RebalanceResult.Status.PARTIAL);
        assertFalse(result.recommended().trades().isEmpty());
        assertTrue(result.recommended().trades().stream().allMatch(trade -> trade.side() == RebalanceResult.TradeSide.BUY));
        assertTrue(result.recommended().trades().stream().anyMatch(trade -> "BND".equals(trade.assetId())));
        assertTrue(totalSpent.compareTo(bd("200")) <= 0);
        assertNotNull(result.metrics().targetThresholdCashGapBefore());
        assertNotNull(result.metrics().targetThresholdCashGapAfter());
        assertTrue(result.metrics().targetThresholdCashGapBefore().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(result.metrics().targetThresholdCashGapAfter().compareTo(result.metrics().targetThresholdCashGapBefore()) <= 0);
    }

    @Test
    void summaryShowsPerTickerBeforeAfterValuesAndPercentages() {
        RebalanceCommand command = new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "5", "100"), position("BND", "0", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("200"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        );

        RebalanceResult result = rebalanceService.rebalance(command);
        String summary = new RebalanceCliFormatter().summary(command, result, false);

        assertTrue(summary.contains("[Per-Ticker Before Vs After]"));
        assertTrue(summary.contains("Ticker"));
        assertTrue(summary.contains("Target"));
        assertTrue(summary.contains("Target %"));
        assertTrue(summary.contains("Value Before"));
        assertTrue(summary.contains("Value After"));
        assertTrue(summary.contains("% Delta"));
        assertTrue(summary.contains("| VOO    | VOO"));
        assertTrue(summary.contains("| BND    | BND"));
        assertTrue(summary.contains("50%"));
        assertTrue(summary.contains("71.4286%"));
        assertTrue(summary.contains("28.5714%"));
        assertTrue(summary.contains("| 200"));
    }

    /**
     * <pre>
     * start: 100% VOO worth 1,000 USD
     * budget: 1,000 USD of new cash
     * target: VOO/BND to 50/50
     * ->
     * post-trade metrics are evaluated against the funded portfolio value, not the original holdings only
     * </pre>
     */
    @Test
    void includesNewCashInPostTradePortfolioValueForBuyOnlyRebalance() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "10", "100"), position("BND", "0", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("1000"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(RebalanceResult.Status.OK, result.status());
        assertEquals(0, result.current().totalValue().compareTo(bd("2000")));
        assertEquals(0, result.metrics().after().maxAbs().compareTo(BigDecimal.ZERO.setScale(12)));
        assertTrue(result.metrics().after().l2().compareTo(result.metrics().before().l2()) < 0);
        assertEquals(0, result.metrics().targetThresholdCashGapAfter().compareTo(BigDecimal.ZERO));
        assertTrue(result.metrics().targetThresholdCashGapBefore().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(0, result.metrics().exactTargetCashGapAfter().compareTo(BigDecimal.ZERO));
    }

    /**
     * <pre>
     * total budget 300 USD
     * split into 3 equal steps
     * some money stays unused in a step
     * ->
     * leftover is carried into the next step
     * </pre>
     */
    @Test
    void buildsMultiStepBuyOnlyPlanWithCarryOver() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY_PLAN_N,
                portfolio(cash("0"), position("VOO", "5", "100"), position("BND", "0", "60")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("300"),
                new RebalanceCommand.Plan(3, RebalanceCommand.StepBudgetMode.EQUAL, RebalanceCommand.SpendMode.UP_TO_BUDGET),
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(3, result.steps().size());
        assertEquals(0, result.steps().get(0).budget().compareTo(bd("100")));
        assertEquals(0, result.steps().get(1).budget().compareTo(bd("140")));
        assertEquals(0, result.steps().get(2).budget().compareTo(bd("120")));
        assertTrue(result.steps().stream().allMatch(step -> !step.trades().isEmpty()));
    }

    /**
     * <pre>
     * VOO is overweight
     * BND is underweight
     * ->
     * engine sells first and then buys with the released cash
     * </pre>
     */
    @Test
    void performsFullRebalanceWithSellsBeforeBuys() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.CLASSIC_FULL_REBALANCE,
                portfolio(cash("0"), position("VOO", "9", "100"), position("BND", "1", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                null,
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertTrue(result.status() == RebalanceResult.Status.OK || result.status() == RebalanceResult.Status.PARTIAL);
        assertEquals(RebalanceResult.TradeSide.SELL, result.recommended().trades().getFirst().side());
        assertTrue(result.recommended().trades().stream().anyMatch(trade -> trade.side() == RebalanceResult.TradeSide.SELL && "VOO".equals(trade.assetId())));
        assertTrue(result.recommended().trades().stream().anyMatch(trade -> trade.side() == RebalanceResult.TradeSide.BUY && "BND".equals(trade.assetId())));
        assertNull(result.metrics().targetThresholdCashGapBefore());
        assertNull(result.metrics().targetThresholdCashGapAfter());
        assertNull(result.metrics().exactTargetCashGapBefore());
        assertNull(result.metrics().exactTargetCashGapAfter());
    }

    /**
     * <pre>
     * AAA + CCC belong to one composite bucket
     * AAA is locked
     * ->
     * composite buy is routed through CCC
     * </pre>
     */
    @Test
    void routesCompositeBuyThroughAllowedMember() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "7", "100"), position("AAA", "1", "100", true, false, false), position("CCC", "0", "100")),
                List.of(target("VOO", "0.70")),
                List.of(new RebalanceCommand.Composite(
                        "LEGACY_TECH",
                        bd("0.30"),
                        List.of("AAA", "CCC"),
                        new RebalanceCommand.TradePolicy(List.of("CCC"), List.of(), List.of("AAA"))
                )),
                budget("100"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        RebalanceResult.Trade compositeTrade = result.recommended().trades().stream()
                .filter(trade -> "LEGACY_TECH".equals(trade.compositeId()))
                .findFirst()
                .orElseThrow();

        assertEquals("CCC", compositeTrade.assetId());
        assertEquals(RebalanceResult.TradeSide.BUY, compositeTrade.side());
    }

    /**
     * <pre>
     * composite buys are routed through VXUS
     * the main allocation leaves a small remainder
     * minNotional is 1 USD
     * ->
     * top-up logic must not emit dust VXUS trades below the minimum order size
     * </pre>
     */
    @Test
    void skipsCompositeTopUpTradesThatViolateMinimumNotional() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(
                        cash("0"),
                        position("AGG", "3.3071", "99.21078891"),
                        position("IAU", "4.1362", "94.49978241"),
                        position("IBKR", "0.6284", "66.18395926"),
                        position("QQQ", "0.8749", "592.79917705"),
                        position("RSP", "1.5047", "193.48042799"),
                        position("SGOV", "3.3119", "100.50726169"),
                        position("VBR", "1.3334", "215.66671666"),
                        position("VEA", "4.1859", "63.94084904"),
                        position("VNQ", "3.5836", "92.02477955"),
                        position("VOO", "4.6639", "608.79092605"),
                        position("VWO", "9.2726", "54.26956841"),
                        position("VXUS", "2.2641", "76.85172916")
                ),
                List.of(
                        target("QQQ", "0.06"),
                        target("VBR", "0.05"),
                        target("VOO", "0.465"),
                        target("AGG", "0.055"),
                        target("SGOV", "0.055"),
                        target("IAU", "0.05"),
                        target("VNQ", "0.055"),
                        target("RSP", "0.05")
                ),
                List.of(new RebalanceCommand.Composite(
                        "INTL",
                        bd("0.16"),
                        List.of("VEA", "VWO", "VXUS"),
                        new RebalanceCommand.TradePolicy(List.of("VXUS"), List.of(), List.of())
                )),
                budget("250"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                new RebalanceCommand.Sizing(true, bd("0.0001"), bd("1"), bd("0.0001")),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertTrue(result.status() == RebalanceResult.Status.OK || result.status() == RebalanceResult.Status.PARTIAL);
        assertTrue(result.recommended().trades().stream()
                .allMatch(trade -> trade.notional().compareTo(bd("1")) >= 0));
        assertEquals(1, result.recommended().trades().stream()
                .filter(trade -> "VXUS".equals(trade.assetId()))
                .count());
    }

    @Test
    void ignoresExplicitlyIgnoredAssetsInRebalanceCalculations() {
        RebalanceCommand withIgnoredIbkr = new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(
                        cash("0"),
                        position("AGG", "3.3071", "99.21078891"),
                        position("IAU", "4.1362", "94.49978241"),
                        position("IBKR", "0.6284", "66.18395926"),
                        position("QQQ", "0.8749", "592.79917705"),
                        position("RSP", "1.5047", "193.48042799"),
                        position("SGOV", "3.3119", "100.50726169"),
                        position("VBR", "1.3334", "215.66671666"),
                        position("VEA", "4.1859", "63.94084904"),
                        position("VNQ", "3.5836", "92.02477955"),
                        position("VOO", "4.6639", "608.79092605"),
                        position("VWO", "9.2726", "54.26956841"),
                        position("VXUS", "2.2641", "76.85172916")
                ),
                List.of(
                        target("QQQ", "0.06"),
                        target("VBR", "0.05"),
                        target("VOO", "0.465"),
                        target("AGG", "0.055"),
                        target("SGOV", "0.055"),
                        target("IAU", "0.05"),
                        target("VNQ", "0.055"),
                        target("RSP", "0.05")
                ),
                List.of(new RebalanceCommand.Composite(
                        "INTL",
                        bd("0.16"),
                        List.of("VEA", "VWO", "VXUS"),
                        new RebalanceCommand.TradePolicy(List.of("VXUS"), List.of(), List.of())
                )),
                budget("250"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                new RebalanceCommand.Sizing(true, bd("0.0001"), bd("1"), bd("0.0001")),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false, List.of("IBKR"))
        );
        RebalanceCommand withoutIbkrPosition = new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(
                        cash("0"),
                        position("AGG", "3.3071", "99.21078891"),
                        position("IAU", "4.1362", "94.49978241"),
                        position("QQQ", "0.8749", "592.79917705"),
                        position("RSP", "1.5047", "193.48042799"),
                        position("SGOV", "3.3119", "100.50726169"),
                        position("VBR", "1.3334", "215.66671666"),
                        position("VEA", "4.1859", "63.94084904"),
                        position("VNQ", "3.5836", "92.02477955"),
                        position("VOO", "4.6639", "608.79092605"),
                        position("VWO", "9.2726", "54.26956841"),
                        position("VXUS", "2.2641", "76.85172916")
                ),
                withIgnoredIbkr.targets(),
                withIgnoredIbkr.composites(),
                withIgnoredIbkr.budget(),
                withIgnoredIbkr.plan(),
                withIgnoredIbkr.threshold(),
                withIgnoredIbkr.fees(),
                withIgnoredIbkr.sizing(),
                withIgnoredIbkr.constraints(),
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        );

        RebalanceResult ignoredResult = rebalanceService.rebalance(withIgnoredIbkr);
        RebalanceResult baselineResult = rebalanceService.rebalance(withoutIbkrPosition);

        assertEquals(0, ignoredResult.current().totalValue().compareTo(baselineResult.current().totalValue()));
        assertEquals(baselineResult.current().weights(), ignoredResult.current().weights());
        assertEquals(baselineResult.metrics().before(), ignoredResult.metrics().before());
        assertEquals(baselineResult.metrics().after(), ignoredResult.metrics().after());
        assertEquals(baselineResult.metrics().targetThresholdCashGapBefore(), ignoredResult.metrics().targetThresholdCashGapBefore());
        assertEquals(baselineResult.metrics().targetThresholdCashGapAfter(), ignoredResult.metrics().targetThresholdCashGapAfter());
        assertEquals(baselineResult.metrics().exactTargetCashGapBefore(), ignoredResult.metrics().exactTargetCashGapBefore());
        assertEquals(baselineResult.metrics().exactTargetCashGapAfter(), ignoredResult.metrics().exactTargetCashGapAfter());
        assertEquals(
                baselineResult.recommended().trades().stream().map(trade -> Map.entry(trade.assetId(), trade.notional())).toList(),
                ignoredResult.recommended().trades().stream().map(trade -> Map.entry(trade.assetId(), trade.notional())).toList()
        );
        assertTrue(ignoredResult.recommended().trades().stream().noneMatch(trade -> "IBKR".equals(trade.assetId())));
    }

    /**
     * <pre>
     * OLD_BUCKET needs a sell
     * no member is allowed to sell
     * ->
     * result becomes INFEASIBLE and exposes the routing blocker
     * </pre>
     */
    @Test
    void reportsInfeasibleWhenCompositeSellRouteIsBlocked() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.CLASSIC_FULL_REBALANCE,
                portfolio(cash("0"), position("OLD1", "10", "100", true, false, false)),
                List.of(target("VOO", "0.90")),
                List.of(new RebalanceCommand.Composite(
                        "OLD_BUCKET",
                        bd("0.10"),
                        List.of("OLD1"),
                        new RebalanceCommand.TradePolicy(List.of(), List.of(), List.of("OLD1"))
                )),
                null,
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(RebalanceResult.Status.INFEASIBLE, result.status());
        assertTrue(result.metrics().bindingConstraints().contains("NO_SELL_MEMBER:OLD_BUCKET"));
    }

    /**
     * <pre>
     * budget is too small for the minimum order rules
     * ->
     * no feasible order can be created
     * </pre>
     */
    @Test
    void respectsSizingAndMinimumOrderConstraints() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "0", "100")),
                List.of(target("VOO", "1.0")),
                List.of(),
                budget("50"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.000001"),
                noFees(),
                new RebalanceCommand.Sizing(false, BigDecimal.ONE, bd("100"), BigDecimal.ONE),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(RebalanceResult.Status.INFEASIBLE, result.status());
        assertTrue(result.metrics().bindingConstraints().stream()
                .anyMatch(constraint -> constraint.contains("BUDGET") || constraint.contains("SIZING")));
    }

    /**
     * <pre>
     * buy-only budget is not enough for exact 50/50
     * ->
     * result estimates threshold cash gap and exact cash diagnostics separately
     * </pre>
     */
    @Test
    void estimatesAdditionalCashForBuyOnlyScenarios() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "10", "100"), position("BND", "0", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("200"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.01"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertNotNull(result.metrics().additionalCashLowerBound());
        assertTrue(result.metrics().additionalCashLowerBound().compareTo(BigDecimal.ZERO) > 0);
        assertNotNull(result.metrics().targetThresholdCashGapBefore());
        assertNotNull(result.metrics().targetThresholdCashGapAfter());
        assertTrue(result.metrics().targetThresholdCashGapBefore().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(result.metrics().targetThresholdCashGapAfter().compareTo(result.metrics().targetThresholdCashGapBefore()) <= 0);
        assertNotNull(result.metrics().exactTargetCashGapBefore());
        assertTrue(result.metrics().exactTargetCashGapBefore().compareTo(BigDecimal.ZERO) > 0);
        assertNotNull(result.metrics().exactTargetCashGapAfter());
        assertTrue(result.metrics().exactTargetCashGapAfter().compareTo(result.metrics().exactTargetCashGapBefore()) <= 0);
        if (result.metrics().additionalCashForExactTarget() != null) {
            assertTrue(result.metrics().additionalCashForExactTarget()
                    .compareTo(result.metrics().additionalCashLowerBound()) >= 0);
        }
    }

    @Test
    void cashGapFallsToZeroOnceThresholdIsSatisfied() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "10", "100"), position("BND", "0", "100")),
                List.of(target("VOO", "0.50"), target("BND", "0.50")),
                List.of(),
                budget("700"),
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.20"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertNotNull(result.metrics().targetThresholdCashGapBefore());
        assertTrue(result.metrics().targetThresholdCashGapBefore().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(0, result.metrics().targetThresholdCashGapAfter().compareTo(BigDecimal.ZERO));
        assertTrue(result.metrics().targetThresholdCashGapAfter().compareTo(result.metrics().targetThresholdCashGapBefore()) < 0);
        assertTrue(result.metrics().after().maxAbs().compareTo(bd("0.20")) <= 0);
        assertNotNull(result.metrics().exactTargetCashGapAfter());
    }

    /**
     * <pre>
     * target weights are 70 and 30
     * sum is 100 instead of 1
     * ->
     * engine normalizes input and records a warning
     * </pre>
     */
    @Test
    void normalizesTargetWeightsWhenTheyDoNotSumToOne() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), position("VOO", "7", "100"), position("BND", "3", "100")),
                List.of(target("VOO", "70"), target("BND", "30")),
                List.of(),
                null,
                null,
                threshold(RebalanceCommand.ThresholdType.MAX_ABS, "0.01"),
                noFees(),
                standardSizing(),
                null,
                options("0.000001", RebalanceCommand.RoundingMode.FLOOR, false, false)
        ));

        assertEquals(RebalanceResult.Status.NO_TRADES, result.status());
        assertTrue(result.debug().inputNormalized());
        assertTrue(result.debug().warnings().contains("Target weights renormalized due to epsilon mismatch"));
    }

    /**
     * <pre>
     * one position has negative quantity
     * ->
     * validation fails before planning starts
     * </pre>
     */
    @Test
    void rejectsInvalidRequestsBeforePlanning() {
        RebalanceResult result = rebalanceService.rebalance(new RebalanceCommand(
                null,
                null,
                "USD",
                RebalanceCommand.Policy.BUY_ONLY,
                portfolio(cash("0"), new RebalanceCommand.Position("VOO", "VOO", "USD", bd("-1"), bd("100"), true, true, true, List.of())),
                List.of(target("VOO", "1.0")),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals(RebalanceResult.Status.INVALID_INPUT, result.status());
        assertEquals("Negative quantities are not allowed", result.reason());
        assertNull(result.metrics());
    }

    private static BigDecimal sumSpent(List<RebalanceResult.Trade> trades) {
        return trades.stream()
                .map(trade -> trade.notional().add(trade.fee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static RebalanceCommand.Portfolio portfolio(RebalanceCommand.Money cash, RebalanceCommand.Position... positions) {
        return new RebalanceCommand.Portfolio(cash, List.of(positions));
    }

    private static RebalanceCommand.Money cash(String amount) {
        return new RebalanceCommand.Money(bd(amount), "USD");
    }

    private static RebalanceCommand.Position position(String assetId, String quantity, String price) {
        return position(assetId, quantity, price, true, true, true);
    }

    private static RebalanceCommand.Position position(
            String assetId,
            String quantity,
            String price,
            boolean allowTrade,
            boolean allowBuy,
            boolean allowSell
    ) {
        return new RebalanceCommand.Position(assetId, assetId, "USD", bd(quantity), bd(price), allowTrade, allowBuy, allowSell, List.of());
    }

    private static RebalanceCommand.TargetAllocation target(String assetId, String targetWeight) {
        return new RebalanceCommand.TargetAllocation(assetId, bd(targetWeight));
    }

    private static RebalanceCommand.Budget budget(String amount) {
        return new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, bd(amount), "USD");
    }

    private static RebalanceCommand.Threshold threshold(RebalanceCommand.ThresholdType type, String value) {
        return new RebalanceCommand.Threshold(type, bd(value));
    }

    private static RebalanceCommand.Fees noFees() {
        return fees("0", "0", "0");
    }

    private static RebalanceCommand.Fees fees(String fixed, String percent, String minFee) {
        return new RebalanceCommand.Fees(bd(fixed), bd(percent), bd(minFee));
    }

    private static RebalanceCommand.Sizing standardSizing() {
        return new RebalanceCommand.Sizing(false, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static RebalanceCommand.Constraints constraints(Integer maxOrders, BigDecimal maxNotionalPerOrder, RebalanceCommand.Risk risk) {
        return new RebalanceCommand.Constraints(false, maxOrders, maxNotionalPerOrder, null, null, null, List.of(), risk);
    }

    private static RebalanceCommand.Risk risk(String maxSingleTradeNotional, boolean killSwitch) {
        return new RebalanceCommand.Risk(maxSingleTradeNotional == null ? null : bd(maxSingleTradeNotional), killSwitch);
    }

    private static RebalanceCommand.Options options(
            String epsilonWeightSum,
            RebalanceCommand.RoundingMode roundingMode,
            boolean allowExtraAssets,
            boolean allowMemberTargets
    ) {
        return options(epsilonWeightSum, roundingMode, allowExtraAssets, allowMemberTargets, List.of());
    }

    private static RebalanceCommand.Options options(
            String epsilonWeightSum,
            RebalanceCommand.RoundingMode roundingMode,
            boolean allowExtraAssets,
            boolean allowMemberTargets,
            List<String> ignoredAssetIds
    ) {
        return new RebalanceCommand.Options(
                bd(epsilonWeightSum),
                null,
                roundingMode,
                false,
                allowExtraAssets,
                allowMemberTargets,
                null,
                ignoredAssetIds
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RebalanceService.class, RebalanceEngine.class, WeightsService.class, FeeModel.class, SizingModel.class})
    static class TestConfig {
    }
}
