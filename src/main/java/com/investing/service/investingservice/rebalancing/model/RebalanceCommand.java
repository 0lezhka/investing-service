package com.investing.service.investingservice.rebalancing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RebalanceCommand(
        UUID requestId,
        Instant asOf,
        String baseCurrency,
        Policy policy,
        Portfolio portfolio,
        List<TargetAllocation> targets,
        List<Composite> composites,
        Budget budget,
        Plan plan,
        Threshold threshold,
        Fees fees,
        Sizing sizing,
        Constraints constraints,
        Options options
) {

    public enum Policy {
        CLASSIC_FULL_REBALANCE,
        BUY_ONLY,
        BUY_ONLY_PLAN_N,
        CONSTRAINED_OPTIMIZATION,
        TAX_AWARE
    }

    public enum BudgetMode {
        NEW_CASH_ONLY,
        TOTAL_AVAILABLE_CASH
    }

    public enum StepBudgetMode {
        EQUAL
    }

    public enum SpendMode {
        UP_TO_BUDGET,
        REQUIRE_FULL_BUDGET
    }

    public enum ThresholdType {
        MAX_ABS,
        L1,
        VALUE
    }

    public enum ObjectivePrimary {
        MIN_L2,
        MIN_L1,
        MIN_MAX_ABS
    }

    public enum ObjectiveSecondary {
        MIN_FEES,
        MIN_TAX,
        MIN_ORDERS
    }

    public enum RoundingMode {
        FLOOR,
        NEAREST,
        CEIL
    }

    public enum TaxLotStrategy {
        FIFO,
        LIFO,
        MIN_GAIN
    }

    public record Money(BigDecimal amount, String currency) {
    }

    public record Portfolio(Money cash, List<Position> positions) {
    }

    public record Position(
            String symbol,
            String assetId,
            String currency,
            BigDecimal quantity,
            BigDecimal price,
            Boolean allowTrade,
            Boolean allowBuy,
            Boolean allowSell,
            List<Lot> lots
    ) {
    }

    public record Lot(
            BigDecimal quantity,
            BigDecimal costPrice,
            LocalDate acquiredAt
    ) {
    }

    public record TargetAllocation(String assetId, BigDecimal targetWeight) {
    }

    public record Budget(BudgetMode mode, BigDecimal amount, String currency) {
    }

    public record Plan(Integer steps, StepBudgetMode stepBudgetMode, SpendMode spendMode) {
    }

    public record Threshold(ThresholdType type, BigDecimal value) {
    }

    public record Fees(
            BigDecimal perOrderFixed,
            BigDecimal percentOfNotional,
            BigDecimal minFee
    ) {
    }

    public record Sizing(
            Boolean fractionalAllowed,
            BigDecimal quantityStep,
            BigDecimal minNotional,
            BigDecimal minQuantity
    ) {
    }

    public record Constraints(
            Boolean noShort,
            Integer maxOrders,
            BigDecimal maxNotionalPerOrder,
            BigDecimal maxNotionalPerAsset,
            Map<String, BigDecimal> minFinalWeight,
            Map<String, BigDecimal> maxFinalWeight,
            List<GroupConstraint> groups,
            Risk risk
    ) {
    }

    public record GroupConstraint(
            String name,
            List<String> assetIds,
            BigDecimal minWeight,
            BigDecimal maxWeight
    ) {
    }

    public record Risk(
            BigDecimal maxSingleTradeNotional,
            Boolean killSwitch
    ) {
    }

    public record Options(
            BigDecimal epsilonWeightSum,
            Objective objective,
            RoundingMode roundingMode,
            Boolean includeIntermediateMetrics,
            Boolean allowExtraAssets,
            Boolean allowMemberTargets,
            TaxLotStrategy taxLotStrategy,
            List<String> ignoredAssetIds
    ) {
    }

    public record Objective(
            ObjectivePrimary primary,
            List<ObjectiveSecondary> secondary
    ) {
    }

    public record Composite(
            String compositeId,
            BigDecimal targetWeight,
            List<String> members,
            TradePolicy tradePolicy
    ) {
    }

    public record TradePolicy(
            List<String> buyMembers,
            List<String> sellMembers,
            List<String> lockedMembers
    ) {
    }
}
