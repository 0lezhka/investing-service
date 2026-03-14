package com.investing.service.investingservice.rebalancing.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RebalanceResult(
        UUID requestId,
        RebalanceCommand.Policy policy,
        Status status,
        String reason,
        Metrics metrics,
        Current current,
        Recommended recommended,
        List<TradeBatch> steps,
        Debug debug
) {

    public enum Status {
        OK,
        INVALID_INPUT,
        INFEASIBLE,
        NO_TRADES,
        PARTIAL
    }

    public enum TradeSide {
        BUY,
        SELL
    }

    public record Metrics(
            DeltaMetrics before,
            DeltaMetrics after,
            BigDecimal targetThresholdCashGapBefore,
            BigDecimal targetThresholdCashGapAfter,
            BigDecimal exactTargetCashGapBefore,
            BigDecimal exactTargetCashGapAfter,
            BigDecimal feesEstimate,
            Turnover turnover,
            BigDecimal additionalCashForExactTarget,
            BigDecimal additionalCashLowerBound,
            List<String> bindingConstraints
    ) {
    }

    public record DeltaMetrics(
            BigDecimal l1,
            BigDecimal l2,
            BigDecimal maxAbs,
            BigDecimal trackingErrorLike
    ) {
    }

    public record Turnover(
            BigDecimal buyNotional,
            BigDecimal sellNotional
    ) {
    }

    public record Current(
            BigDecimal totalValue,
            Map<String, BigDecimal> weights
    ) {
    }

    public record Recommended(List<Trade> trades) {
    }

    public record Trade(
            Integer step,
            String assetId,
            TradeSide side,
            BigDecimal quantity,
            BigDecimal notional,
            BigDecimal fee,
            String compositeId
    ) {
    }

    public record TradeBatch(
            Integer step,
            BigDecimal budget,
            List<Trade> trades,
            DeltaMetrics metricsAfterStep
    ) {
    }

    public record Debug(
            List<String> warnings,
            boolean inputNormalized
    ) {
    }
}
