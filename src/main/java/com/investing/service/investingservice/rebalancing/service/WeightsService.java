package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WeightsService {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public Snapshot snapshot(Collection<AssetState> assets, BigDecimal cash) {
        BigDecimal total = cash == null ? BigDecimal.ZERO : cash;
        for (AssetState asset : assets) {
            total = total.add(asset.marketValue());
        }

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (AssetState asset : assets) {
            BigDecimal weight = total.signum() == 0
                    ? BigDecimal.ZERO
                    : asset.marketValue().divide(total, 12, RoundingMode.HALF_UP);
            weights.put(asset.assetId(), weight);
        }

        return new Snapshot(total, weights);
    }

    public RebalanceResult.DeltaMetrics deltaMetrics(Map<String, BigDecimal> weights, Map<String, BigDecimal> targets) {
        BigDecimal l1 = BigDecimal.ZERO;
        BigDecimal l2Squared = BigDecimal.ZERO;
        BigDecimal maxAbs = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> entry : targets.entrySet()) {
            BigDecimal weight = weights.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal delta = weight.subtract(entry.getValue(), MATH_CONTEXT);
            BigDecimal abs = delta.abs();
            l1 = l1.add(abs);
            l2Squared = l2Squared.add(delta.multiply(delta, MATH_CONTEXT));
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
        }

        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            if (targets.containsKey(entry.getKey())) {
                continue;
            }
            BigDecimal abs = entry.getValue().abs();
            l1 = l1.add(abs);
            l2Squared = l2Squared.add(entry.getValue().multiply(entry.getValue(), MATH_CONTEXT));
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
        }

        BigDecimal l2 = BigDecimal.valueOf(Math.sqrt(l2Squared.doubleValue())).setScale(12, RoundingMode.HALF_UP);
        return new RebalanceResult.DeltaMetrics(
                l1.setScale(12, RoundingMode.HALF_UP),
                l2,
                maxAbs.setScale(12, RoundingMode.HALF_UP),
                l2
        );
    }

    public boolean withinThreshold(RebalanceResult.DeltaMetrics metrics, RebalanceCommand.Threshold threshold, BigDecimal totalValue) {
        if (threshold == null || threshold.value() == null || threshold.type() == null) {
            return false;
        }

        return switch (threshold.type()) {
            case MAX_ABS -> metrics.maxAbs().compareTo(threshold.value()) <= 0;
            case L1 -> metrics.l1().compareTo(threshold.value()) <= 0;
            case VALUE -> totalValue.multiply(metrics.l1()).compareTo(threshold.value()) <= 0;
        };
    }

    public record Snapshot(BigDecimal totalValue, Map<String, BigDecimal> weights) {
    }

    public record AssetState(
            String assetId,
            String symbol,
            BigDecimal quantity,
            BigDecimal price,
            boolean allowTrade,
            boolean allowBuy,
            boolean allowSell,
            java.util.List<RebalanceCommand.Lot> lots
    ) {
        public BigDecimal marketValue() {
            if (quantity == null || price == null) {
                return BigDecimal.ZERO;
            }
            return quantity.multiply(price);
        }
    }
}
