package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class RebalanceCliFormatter {

    private static final String NL = System.lineSeparator();
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_CYAN = "\u001B[36m";

    public String usage() {
        return """
                Usage:
                  rebalance help
                  rebalance run --input <path|-> [--output summary|json] [--pretty]
                                [--load-current-prices]
                                [--budget <decimal>] [--policy <POLICY>] [--steps <int>]
                                [--threshold-type <TYPE>] [--threshold-value <decimal>]
                                [--fail-on-partial] [--fail-on-no-trades]
                  rebalance validate --input <path|-> [--output summary|json]
                  rebalance template [--policy <POLICY>] [--with-composite] [--pretty]

                Examples:
                  rebalance run --input request.json
                  rebalance run --input request.json --load-current-prices
                  rebalance run --input request.json --output json --pretty
                  rebalance run --input - --policy BUY_ONLY --budget 500
                  rebalance validate --input request.json
                  rebalance template --policy BUY_ONLY_PLAN_N --with-composite --pretty
                """;
    }

    public String summary(RebalanceCommand request, RebalanceResult result, boolean ansiEnabled) {
        StringBuilder builder = new StringBuilder();
        builder.append(renderBanner("REBALANCE SUMMARY", result.status().name(), statusColor(result.status()), ansiEnabled));
        if (result.reason() != null && !result.reason().isBlank()) {
            builder.append("Reason: ").append(result.reason()).append(NL);
        }
        builder.append(section("Execution Context", ansiEnabled));
        appendKeyValue(builder, "Request ID", value(result.requestId()), 18);
        appendKeyValue(builder, "Policy", value(result.policy()), 18);
        appendKeyValue(builder, "Base Currency", value(request == null ? null : request.baseCurrency()), 18);
        appendKeyValue(builder, "As Of", value(request == null ? null : request.asOf()), 18);
        appendKeyValue(builder, "Positions", String.valueOf(count(request == null || request.portfolio() == null ? null : request.portfolio().positions())), 18);
        appendKeyValue(builder, "Targets", String.valueOf(count(request == null ? null : request.targets())), 18);
        appendKeyValue(builder, "Composites", String.valueOf(count(request == null ? null : request.composites())), 18);
        if (result.current() != null) {
            appendKeyValue(builder, "Current Value", value(result.current().totalValue()), 18);
        }

        builder.append(section("Portfolio And Plan", ansiEnabled));
        if (request != null && request.portfolio() != null && request.portfolio().cash() != null) {
            appendKeyValue(builder, "Cash", formatMoney(request.portfolio().cash().amount(), request.portfolio().cash().currency()), 18);
        } else {
            appendKeyValue(builder, "Cash", "n/a", 18);
        }
        if (request != null && request.budget() != null) {
            appendKeyValue(builder, "Budget", formatBudget(request.budget()), 18);
        } else {
            appendKeyValue(builder, "Budget", "n/a", 18);
        }
        if (request != null && request.threshold() != null) {
            appendKeyValue(builder, "Threshold", formatThreshold(request.threshold()), 18);
        } else {
            appendKeyValue(builder, "Threshold", "n/a", 18);
        }
        if (request != null && request.plan() != null) {
            appendKeyValue(builder, "Plan", formatPlan(request.plan()), 18);
        } else {
            appendKeyValue(builder, "Plan", "not configured", 18);
        }

        builder.append(section("Metrics Before Vs After", ansiEnabled));
        if (result.metrics() != null) {
            builder.append(renderMetricComparison(result.metrics(), ansiEnabled));
            builder.append("Fees Estimate        : ").append(value(result.metrics().feesEstimate())).append(NL);
            if (result.metrics().turnover() != null) {
                builder.append("Turnover             : buy=").append(value(result.metrics().turnover().buyNotional()))
                        .append(", sell=").append(value(result.metrics().turnover().sellNotional())).append(NL);
            }
            builder.append("Additional Cash Exact: exact=").append(value(result.metrics().additionalCashForExactTarget()))
                    .append(", lowerBound=").append(value(result.metrics().additionalCashLowerBound())).append(NL);
            builder.append("Binding Constraints  : ").append(join(result.metrics().bindingConstraints())).append(NL);
        } else {
            builder.append("No metrics available").append(NL);
        }

        builder.append(section("Per-Ticker Before Vs After", ansiEnabled));
        builder.append(renderAllocationComparisonTable(request, result, ansiEnabled));

        builder.append(section("Recommended Trades", ansiEnabled));
        if (result.recommended() == null || result.recommended().trades() == null || result.recommended().trades().isEmpty()) {
            builder.append("No trades recommended").append(NL);
        } else {
            builder.append(renderTradeTable(result.recommended().trades()));
        }

        builder.append(section("Step Breakdown", ansiEnabled));
        if (result.steps() != null && !result.steps().isEmpty()) {
            for (RebalanceResult.TradeBatch batch : result.steps()) {
                builder.append("Step ").append(value(batch.step()))
                        .append(" | budget=").append(value(batch.budget()))
                        .append(" | trades=").append(batch.trades() == null ? 0 : batch.trades().size())
                        .append(" | metrics=").append(formatMetrics(batch.metricsAfterStep()))
                        .append(NL);
            }
        } else {
            builder.append("No step breakdown available").append(NL);
        }

        builder.append(section("Warnings And Notes", ansiEnabled));
        List<String> notes = new ArrayList<>();
        if (result.debug() != null && result.debug().warnings() != null) {
            notes.addAll(result.debug().warnings());
        }
        if (result.debug() != null && result.debug().inputNormalized()) {
            notes.add("Input normalized before planning");
        }
        if (notes.isEmpty()) {
            builder.append("No warnings or notes").append(NL);
        } else {
            for (String note : notes) {
                builder.append("- ").append(note).append(NL);
            }
        }
        return builder.toString();
    }

    public String validationSummary(RebalanceCommand request, RebalanceResult result, boolean ansiEnabled) {
        StringBuilder builder = new StringBuilder();
        String label = result.status() == RebalanceResult.Status.INVALID_INPUT ? "FAILED" : "OK";
        builder.append(renderBanner("VALIDATION SUMMARY", label,
                result.status() == RebalanceResult.Status.INVALID_INPUT ? ANSI_RED : ANSI_GREEN, ansiEnabled));
        builder.append(section("Request Context", ansiEnabled));
        appendKeyValue(builder, "Policy", value(result.policy() != null ? result.policy() : request == null ? null : request.policy()), 18);
        appendKeyValue(builder, "Base Currency", value(request == null ? null : request.baseCurrency()), 18);
        appendKeyValue(builder, "Positions", String.valueOf(count(request == null || request.portfolio() == null ? null : request.portfolio().positions())), 18);
        appendKeyValue(builder, "Targets", String.valueOf(count(request == null ? null : request.targets())), 18);
        builder.append(section("Validation Result", ansiEnabled));
        if (result.status() == RebalanceResult.Status.INVALID_INPUT) {
            builder.append("Status               : FAILED").append(NL);
            builder.append("Reason               : ").append(value(result.reason())).append(NL);
            return builder.toString();
        }
        builder.append("Status               : OK").append(NL);
        builder.append("Reason               : input accepted").append(NL);
        return builder.toString();
    }

    public RebalanceCommand template(RebalanceCommand.Policy policy, boolean withComposite) {
        RebalanceCommand.Position voo = new RebalanceCommand.Position(
                "VOO",
                "VOO",
                "USD",
                new BigDecimal("5"),
                new BigDecimal("100"),
                true,
                true,
                true,
                List.of()
        );
        RebalanceCommand.Position bnd = new RebalanceCommand.Position(
                "BND",
                "BND",
                "USD",
                BigDecimal.ZERO,
                new BigDecimal("100"),
                true,
                true,
                true,
                List.of()
        );
        RebalanceCommand.Position legacy = new RebalanceCommand.Position(
                "AAA",
                "AAA",
                "USD",
                new BigDecimal("2"),
                new BigDecimal("100"),
                true,
                false,
                false,
                List.of()
        );

        List<RebalanceCommand.Position> positions = withComposite ? List.of(voo, legacy) : List.of(voo, bnd);
        List<RebalanceCommand.TargetAllocation> targets = withComposite
                ? List.of(new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.7")))
                : List.of(
                        new RebalanceCommand.TargetAllocation("VOO", new BigDecimal("0.6")),
                        new RebalanceCommand.TargetAllocation("BND", new BigDecimal("0.4"))
                );
        List<RebalanceCommand.Composite> composites = withComposite
                ? List.of(new RebalanceCommand.Composite(
                        "LEGACY_BUCKET",
                        new BigDecimal("0.3"),
                        List.of("AAA"),
                        new RebalanceCommand.TradePolicy(List.of(), List.of(), List.of("AAA"))
                ))
                : List.of();

        RebalanceCommand.Plan plan = policy == RebalanceCommand.Policy.BUY_ONLY_PLAN_N
                ? new RebalanceCommand.Plan(3, RebalanceCommand.StepBudgetMode.EQUAL, RebalanceCommand.SpendMode.UP_TO_BUDGET)
                : null;

        return new RebalanceCommand(
                null,
                null,
                "USD",
                policy,
                new RebalanceCommand.Portfolio(
                        new RebalanceCommand.Money(BigDecimal.ZERO, "USD"),
                        positions
                ),
                targets,
                composites,
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, new BigDecimal("500"), "USD"),
                plan,
                new RebalanceCommand.Threshold(RebalanceCommand.ThresholdType.MAX_ABS, new BigDecimal("0.01")),
                new RebalanceCommand.Fees(new BigDecimal("1.00"), BigDecimal.ZERO, new BigDecimal("1.00")),
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

    private String formatMetrics(RebalanceResult.DeltaMetrics metrics) {
        if (metrics == null) {
            return "n/a";
        }
        return "l1=" + value(metrics.l1())
                + ", l2=" + value(metrics.l2())
                + ", maxAbs=" + value(metrics.maxAbs())
                + ", tracking=" + value(metrics.trackingErrorLike());
    }

    private String renderMetricComparison(RebalanceResult.Metrics metrics, boolean ansiEnabled) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Metric", "Before", "After", "Delta"});
        rows.add(metricRow("L1", metrics.before() == null ? null : metrics.before().l1(), metrics.after() == null ? null : metrics.after().l1()));
        rows.add(metricRow("L2", metrics.before() == null ? null : metrics.before().l2(), metrics.after() == null ? null : metrics.after().l2()));
        rows.add(metricRow("Max Abs", metrics.before() == null ? null : metrics.before().maxAbs(), metrics.after() == null ? null : metrics.after().maxAbs()));
        rows.add(metricRow("Tracking", metrics.before() == null ? null : metrics.before().trackingErrorLike(), metrics.after() == null ? null : metrics.after().trackingErrorLike()));
        rows.add(metricRow("Cash Gap", metrics.targetThresholdCashGapBefore(), metrics.targetThresholdCashGapAfter()));
        return renderTable(rows, ansiEnabled ? ANSI_CYAN : null);
    }

    private String[] metricRow(String label, BigDecimal before, BigDecimal after) {
        return new String[]{label, value(before), value(after), delta(before, after)};
    }

    private String renderTradeTable(List<RebalanceResult.Trade> trades) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Step", "Side", "Asset", "Quantity", "Notional", "Fee", "Composite"});
        for (RebalanceResult.Trade trade : trades) {
            rows.add(new String[]{
                    value(trade.step()),
                    value(trade.side()),
                    value(trade.assetId()),
                    value(trade.quantity()),
                    value(trade.notional()),
                    value(trade.fee()),
                    value(trade.compositeId())
            });
        }
        return renderTable(rows, null);
    }

    private String renderAllocationComparisonTable(RebalanceCommand request, RebalanceResult result, boolean ansiEnabled) {
        Map<String, AllocationRow> rowsByAssetId = allocationRows(request, result);
        if (rowsByAssetId.isEmpty()) {
            return "No per-ticker allocation data available" + NL;
        }

        Set<String> ignoredAssetIds = ignoredAssetIds(request);
        BigDecimal beforeTotal = portfolioTotal(request, ignoredAssetIds);
        BigDecimal afterTotal = result != null && result.current() != null ? result.current().totalValue() : beforeTotal;

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Ticker", "Target", "Target %", "Value Before", "Value After", "Value Delta", "% Before", "% After", "% Delta"});
        for (AllocationRow row : rowsByAssetId.values()) {
            BigDecimal afterValue = row.beforeValue.add(row.tradeDelta);
            rows.add(new String[]{
                    row.ticker,
                    row.targetId,
                    formatTargetPercentage(row.targetWeight),
                    value(row.beforeValue),
                    value(afterValue),
                    delta(row.beforeValue, afterValue),
                    formatPercentage(row.beforeValue, beforeTotal),
                    formatPercentage(afterValue, afterTotal),
                    delta(percentage(row.beforeValue, beforeTotal), percentage(afterValue, afterTotal), true)
            });
        }
        return renderTable(rows, ansiEnabled ? ANSI_CYAN : null);
    }

    private String renderTable(List<String[]> rows, String headerColor) {
        int columnCount = rows.stream().mapToInt(row -> row.length).max().orElse(0);
        int[] widths = new int[columnCount];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], safe(row[i]).length());
            }
        }

        StringBuilder builder = new StringBuilder();
        String border = buildBorder(widths);
        builder.append(border).append(NL);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            String[] row = rows.get(rowIndex);
            builder.append("|");
            for (int i = 0; i < columnCount; i++) {
                String cell = padRight(i < row.length ? safe(row[i]) : "", widths[i]);
                if (rowIndex == 0 && headerColor != null) {
                    cell = colorize(cell, headerColor, true);
                }
                builder.append(" ").append(cell).append(" |");
            }
            builder.append(NL);
            if (rowIndex == 0) {
                builder.append(border).append(NL);
            }
        }
        builder.append(border).append(NL);
        return builder.toString();
    }

    private String buildBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append("+");
        }
        return border.toString();
    }

    private String renderBanner(String title, String status, String color, boolean ansiEnabled) {
        String left = title + " | Status: " + status;
        String line = "=".repeat(Math.max(32, left.length()));
        String banner = line + NL + left + NL + line + NL;
        return ansiEnabled ? colorize(banner, color, true) : banner;
    }

    private String section(String title, boolean ansiEnabled) {
        String text = "[" + title + "]" + NL;
        return ansiEnabled ? colorize(text, ANSI_BLUE, true) : text;
    }

    private void appendKeyValue(StringBuilder builder, String key, String value, int width) {
        builder.append(padRight(key, width)).append(": ").append(value).append(NL);
    }

    private String formatBudget(RebalanceCommand.Budget budget) {
        return value(budget.mode()) + " " + formatMoney(budget.amount(), budget.currency());
    }

    private String formatThreshold(RebalanceCommand.Threshold threshold) {
        return value(threshold.type()) + " " + value(threshold.value());
    }

    private String formatPlan(RebalanceCommand.Plan plan) {
        return "steps=" + value(plan.steps())
                + ", stepBudgetMode=" + value(plan.stepBudgetMode())
                + ", spendMode=" + value(plan.spendMode());
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null && currency == null) {
            return "n/a";
        }
        if (currency == null) {
            return value(amount);
        }
        if (amount == null) {
            return currency;
        }
        return amount + " " + currency;
    }

    private int count(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String delta(BigDecimal before, BigDecimal after) {
        return delta(before, after, false);
    }

    private String delta(BigDecimal before, BigDecimal after, boolean percentage) {
        if (before == null || after == null) {
            return "n/a";
        }
        BigDecimal delta = after.subtract(before);
        return percentage ? formatPercentValue(delta) : delta.toPlainString();
    }

    private Map<String, AllocationRow> allocationRows(RebalanceCommand request, RebalanceResult result) {
        Map<String, AllocationRow> rows = new LinkedHashMap<>();
        Map<String, TargetInfo> targetsByAssetId = targetsByAssetId(request);
        if (request != null && request.portfolio() != null && request.portfolio().positions() != null) {
            for (RebalanceCommand.Position position : request.portfolio().positions()) {
                if (position == null || !hasText(position.assetId())) {
                    continue;
                }
                String assetId = position.assetId();
                String ticker = hasText(position.symbol()) ? position.symbol() : assetId;
                BigDecimal beforeValue = marketValue(position.quantity(), position.price());
                TargetInfo targetInfo = targetsByAssetId.get(assetId);
                boolean ignored = ignoredAssetIds(request).contains(assetId);
                rows.compute(assetId, (key, existing) -> existing == null
                        ? new AllocationRow(ticker, targetInfo == null ? null : targetInfo.targetId(), targetInfo == null ? null : targetInfo.targetWeight(), beforeValue, BigDecimal.ZERO, ignored)
                        : existing.addBeforeValue(beforeValue));
            }
        }

        if (result != null && result.recommended() != null && result.recommended().trades() != null) {
            for (RebalanceResult.Trade trade : result.recommended().trades()) {
                if (trade == null || !hasText(trade.assetId())) {
                    continue;
                }
                String assetId = trade.assetId();
                BigDecimal signedNotional = signedNotional(trade);
                TargetInfo targetInfo = targetsByAssetId.get(assetId);
                boolean ignored = ignoredAssetIds(request).contains(assetId);
                rows.compute(assetId, (key, existing) -> {
                    if (existing == null) {
                        return new AllocationRow(assetId, targetInfo == null ? null : targetInfo.targetId(), targetInfo == null ? null : targetInfo.targetWeight(), BigDecimal.ZERO, signedNotional, ignored);
                    }
                    return existing.addTradeDelta(signedNotional);
                });
            }
        }
        return rows;
    }

    private Map<String, TargetInfo> targetsByAssetId(RebalanceCommand request) {
        Map<String, TargetInfo> targetsByAssetId = new LinkedHashMap<>();
        if (request != null && request.targets() != null) {
            for (RebalanceCommand.TargetAllocation target : request.targets()) {
                if (target == null || !hasText(target.assetId())) {
                    continue;
                }
                targetsByAssetId.put(target.assetId(), new TargetInfo(target.assetId(), target.targetWeight()));
            }
        }
        if (request != null && request.composites() != null) {
            for (RebalanceCommand.Composite composite : request.composites()) {
                if (composite == null || !hasText(composite.compositeId()) || composite.members() == null) {
                    continue;
                }
                for (String member : composite.members()) {
                    if (!hasText(member) || targetsByAssetId.containsKey(member)) {
                        continue;
                    }
                    targetsByAssetId.put(member, new TargetInfo(composite.compositeId(), composite.targetWeight()));
                }
            }
        }
        return targetsByAssetId;
    }

    private BigDecimal signedNotional(RebalanceResult.Trade trade) {
        if (trade == null || trade.notional() == null) {
            return BigDecimal.ZERO;
        }
        return trade.side() == RebalanceResult.TradeSide.SELL ? trade.notional().negate() : trade.notional();
    }

    private BigDecimal marketValue(BigDecimal quantity, BigDecimal price) {
        if (quantity == null || price == null) {
            return BigDecimal.ZERO;
        }
        return quantity.multiply(price);
    }

    private BigDecimal portfolioTotal(RebalanceCommand request, Set<String> ignoredAssetIds) {
        BigDecimal total = BigDecimal.ZERO;
        if (request != null && request.portfolio() != null && request.portfolio().cash() != null && request.portfolio().cash().amount() != null) {
            total = total.add(request.portfolio().cash().amount());
        }
        if (request != null && request.portfolio() != null && request.portfolio().positions() != null) {
            for (RebalanceCommand.Position position : request.portfolio().positions()) {
                if (position == null) {
                    continue;
                }
                if (ignoredAssetIds.contains(position.assetId())) {
                    continue;
                }
                total = total.add(marketValue(position.quantity(), position.price()));
            }
        }
        return total;
    }

    private String formatPercentage(BigDecimal value, BigDecimal total) {
        BigDecimal percentage = percentage(value, total);
        return percentage == null ? "n/a" : formatPercentValue(percentage);
    }

    private BigDecimal percentage(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() == 0) {
            return null;
        }
        return value.multiply(new BigDecimal("100")).divide(total, 4, java.math.RoundingMode.HALF_UP);
    }

    private String formatPercentValue(BigDecimal percentage) {
        return percentage.stripTrailingZeros().toPlainString() + "%";
    }

    private String formatTargetPercentage(BigDecimal targetWeight) {
        if (targetWeight == null) {
            return "n/a";
        }
        return formatPercentValue(targetWeight.multiply(new BigDecimal("100")));
    }

    private Set<String> ignoredAssetIds(RebalanceCommand request) {
        if (request == null || request.options() == null || request.options().ignoredAssetIds() == null) {
            return Set.of();
        }
        return request.options().ignoredAssetIds().stream()
                .filter(this::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String colorize(String text, String color, boolean bold) {
        if (color == null) {
            return text;
        }
        return (bold ? ANSI_BOLD : "") + color + text + ANSI_RESET;
    }

    private String statusColor(RebalanceResult.Status status) {
        return switch (status) {
            case OK -> ANSI_GREEN;
            case PARTIAL, NO_TRADES -> ANSI_YELLOW;
            case INVALID_INPUT, INFEASIBLE -> ANSI_RED;
        };
    }

    public boolean ansiEnabled(boolean interactive) {
        String noColor = System.getenv("NO_COLOR");
        return interactive && (noColor == null || noColor.isBlank());
    }

    private String safe(String value) {
        return value == null ? "n/a" : value;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return values.stream().collect(Collectors.joining(", "));
    }

    private String value(Object value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    private String padRight(String value, int width) {
        return String.format("%-" + width + "s", value);
    }

    private record AllocationRow(String ticker, String targetId, BigDecimal targetWeight, BigDecimal beforeValue, BigDecimal tradeDelta, boolean ignored) {

        private AllocationRow {
            ticker = Objects.requireNonNullElse(ticker, "n/a");
            targetId = ignored ? "ignored" : targetId != null && !targetId.isBlank() ? targetId : "n/a";
            beforeValue = beforeValue == null ? BigDecimal.ZERO : beforeValue;
            tradeDelta = tradeDelta == null ? BigDecimal.ZERO : tradeDelta;
            targetWeight = ignored ? null : targetWeight;
        }

        private AllocationRow addBeforeValue(BigDecimal additionalBeforeValue) {
            return new AllocationRow(ticker, targetId, targetWeight, beforeValue.add(additionalBeforeValue == null ? BigDecimal.ZERO : additionalBeforeValue), tradeDelta, ignored);
        }

        private AllocationRow addTradeDelta(BigDecimal additionalTradeDelta) {
            return new AllocationRow(ticker, targetId, targetWeight, beforeValue, tradeDelta.add(additionalTradeDelta == null ? BigDecimal.ZERO : additionalTradeDelta), ignored);
        }
    }

    private record TargetInfo(String targetId, BigDecimal targetWeight) {
    }
}
