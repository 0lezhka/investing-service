package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class RebalanceEngine {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal EXTRA_CASH_CAP = new BigDecimal("1000000");
    private static final int EXTRA_CASH_ITERATIONS = 24;

    private final WeightsService weightsService;
    private final FeeModel feeModel;
    private final SizingModel sizingModel;

    public RebalanceEngine(WeightsService weightsService, FeeModel feeModel, SizingModel sizingModel) {
        this.weightsService = weightsService;
        this.feeModel = feeModel;
        this.sizingModel = sizingModel;
    }

    public RebalanceResult rebalance(RebalanceCommand command) {
        Validation validation = validate(command);
        if (!validation.valid()) {
            return error(command, RebalanceResult.Status.INVALID_INPUT, validation.reason());
        }

        if (killSwitch(command)) {
            PlanningContext context = createContext(command);
            EntitySnapshot snapshot = entitySnapshot(context);
            RebalanceResult.DeltaMetrics before = weightsService.deltaMetrics(snapshot.weights, context.targetWeights);
            TargetThresholdCashGap thresholdCashGap = targetThresholdCashGap(command, context);
            ExactTargetCashGap cashGap = exactTargetCashGap(command, context);
            return buildResult(command, RebalanceResult.Status.NO_TRADES, "Kill switch enabled", snapshot, before, before,
                    List.of(), List.of(), List.of("Constraint: KILL_SWITCH"), false,
                    thresholdCashGap.before, thresholdCashGap.after, cashGap.before, cashGap.after, ZERO, ZERO, null, ZERO);
        }

        Normalized normalized = normalize(command);
        PlanningContext context = createContext(normalized.command);
        EntitySnapshot beforeSnapshot = entitySnapshot(context);
        RebalanceResult.DeltaMetrics before = weightsService.deltaMetrics(beforeSnapshot.weights, context.targetWeights);
        TargetThresholdCashGap beforeThresholdCashGap = targetThresholdCashGap(normalized.command, context);
        ExactTargetCashGap beforeCashGap = exactTargetCashGap(normalized.command, context);

        if (weightsService.withinThreshold(before, normalized.command.threshold(), beforeSnapshot.totalValue)) {
            return buildResult(normalized.command, RebalanceResult.Status.NO_TRADES, "Portfolio is already within threshold",
                    beforeSnapshot, before, before, List.of(), List.of(), normalized.warnings, normalized.normalized,
                    beforeThresholdCashGap.before, beforeThresholdCashGap.after,
                    beforeCashGap.before, beforeCashGap.after, ZERO, ZERO, null, ZERO);
        }

        Execution execution = normalized.command.policy() == RebalanceCommand.Policy.BUY_ONLY_PLAN_N
                ? runPlan(normalized.command, context)
                : runSingle(normalized.command, context.copy(), budget(normalized.command), 1, isBuyOnly(normalized.command.policy()));

        EntitySnapshot afterSnapshot = entitySnapshot(execution.context);
        RebalanceResult.DeltaMetrics after = weightsService.deltaMetrics(afterSnapshot.weights, execution.context.targetWeights);
        RebalanceResult.Status status = deriveStatus(execution, before, after);
        String reason = status == RebalanceResult.Status.PARTIAL ? "Constraints prevented full execution"
                : status == RebalanceResult.Status.INFEASIBLE ? "No feasible trades produced" : null;
        AdditionalCash extra = additionalCash(normalized.command, context);
        TargetThresholdCashGap afterThresholdCashGap = targetThresholdCashGap(normalized.command, context, execution.context);
        ExactTargetCashGap afterCashGap = exactTargetCashGap(normalized.command, context, execution.context);

        return buildResult(normalized.command, status, reason, afterSnapshot, before, after, execution.trades, execution.batches,
                merge(normalized.warnings, execution.warnings), normalized.normalized,
                afterThresholdCashGap.before, afterThresholdCashGap.after,
                afterCashGap.before, afterCashGap.after, execution.fees, execution.sellNotional, extra.exact, extra.lowerBound);
    }

    private Execution runPlan(RebalanceCommand command, PlanningContext initialContext) {
        int steps = command.plan() != null && command.plan().steps() != null && command.plan().steps() > 0 ? command.plan().steps() : 1;
        BigDecimal totalBudget = budget(command);
        BigDecimal baseStep = totalBudget.divide(BigDecimal.valueOf(steps), 8, RoundingMode.DOWN);
        PlanningContext current = initialContext.copy();
        List<RebalanceResult.Trade> trades = new ArrayList<>();
        List<RebalanceResult.TradeBatch> batches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal fees = ZERO;
        BigDecimal sells = ZERO;
        BigDecimal carryOver = ZERO;

        for (int step = 1; step <= steps; step++) {
            BigDecimal stepBudget = step == steps
                    ? totalBudget.subtract(baseStep.multiply(BigDecimal.valueOf(steps - 1))).add(carryOver)
                    : baseStep.add(carryOver);
            Execution stepExecution = runSingle(command, current.copy(), stepBudget, step, true);
            if (stepExecution.trades.isEmpty()) {
                if (!batches.isEmpty()) {
                    warnings.add("Constraint: NO_FURTHER_FEASIBLE_TRADES");
                }
                break;
            }

            BigDecimal spent = buyNotional(stepExecution.trades).add(stepExecution.fees);
            carryOver = stepBudget.subtract(spent).max(ZERO);
            current = stepExecution.context;
            trades.addAll(stepExecution.trades);
            batches.addAll(stepExecution.batches);
            warnings.addAll(stepExecution.warnings);
            fees = fees.add(stepExecution.fees);
            sells = sells.add(stepExecution.sellNotional);
        }

        return new Execution(current, trades, batches, warnings, fees, sells);
    }

    private Execution runSingle(RebalanceCommand command, PlanningContext context, BigDecimal budget, int step, boolean buyOnly) {
        return buyOnly ? buyOnlyExecution(command, context, budget, step) : fullRebalanceExecution(command, context, budget, step);
    }

    private Execution buyOnlyExecution(RebalanceCommand command, PlanningContext context, BigDecimal budget, int step) {
        if (budget.signum() > 0) {
            context.cash = switch (command.budget() == null ? RebalanceCommand.BudgetMode.NEW_CASH_ONLY : command.budget().mode()) {
                case NEW_CASH_ONLY -> context.cash.add(budget);
                case TOTAL_AVAILABLE_CASH -> context.cash;
            };
        }
        EntitySnapshot start = entitySnapshot(context);
        BigDecimal futureTotal = start.totalValue;
        List<EntityNeed> needs = underweightEntities(context, futureTotal);
        BigDecimal sumNeed = needs.stream().map(need -> need.needValue).reduce(ZERO, BigDecimal::add);
        BigDecimal remaining = budget;
        BigDecimal fees = ZERO;
        List<RebalanceResult.Trade> trades = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> blockedEntities = new LinkedHashSet<>();

        for (EntityNeed need : needs) {
            if (trades.size() >= maxOrders(command)) {
                warnings.add("Constraint: MAX_ORDERS");
                break;
            }
            if (sumNeed.signum() <= 0 || remaining.signum() <= 0) {
                break;
            }
            BigDecimal allocation = budget.multiply(need.needValue, MC).divide(sumNeed, 8, RoundingMode.DOWN);
            OrderAttempt attempt = attemptEntityOrder(command, context, need.entityId, RebalanceResult.TradeSide.BUY, allocation, remaining, step);
            if (attempt.trade != null) {
                trades.add(attempt.trade);
                remaining = remaining.subtract(attempt.spent).max(ZERO);
                fees = fees.add(attempt.fee);
            } else if (attempt.warning != null) {
                warnings.add(attempt.warning);
                blockedEntities.add(need.entityId);
            }
        }

        minimalStepTopUp(command, context, step, trades, warnings, blockedEntities, remaining);
        BigDecimal finalFees = trades.stream().map(RebalanceResult.Trade::fee).filter(Objects::nonNull).reduce(ZERO, BigDecimal::add);
        EntitySnapshot after = entitySnapshot(context);
        RebalanceResult.DeltaMetrics afterMetrics = weightsService.deltaMetrics(after.weights, context.targetWeights);
        List<RebalanceResult.TradeBatch> batches = trades.isEmpty() ? List.of()
                : List.of(new RebalanceResult.TradeBatch(step, scale(budget), trades, afterMetrics));
        return new Execution(context, trades, batches, warnings, finalFees, ZERO);
    }

    private Execution fullRebalanceExecution(RebalanceCommand command, PlanningContext context, BigDecimal budget, int step) {
        List<String> warnings = new ArrayList<>();
        List<RebalanceResult.Trade> trades = new ArrayList<>();
        BigDecimal fees = ZERO;
        BigDecimal sells = ZERO;
        EntitySnapshot snapshot = entitySnapshot(context);
        Map<String, BigDecimal> diffs = entityDiffs(context, snapshot.totalValue);

        List<EntityNeed> sellNeeds = diffs.entrySet().stream()
                .filter(entry -> entry.getValue().signum() < 0)
                .map(entry -> new EntityNeed(entry.getKey(), entry.getValue().abs()))
                .sorted((left, right) -> right.needValue.compareTo(left.needValue))
                .toList();

        for (EntityNeed need : sellNeeds) {
            if (trades.size() >= maxOrders(command)) {
                warnings.add("Constraint: MAX_ORDERS");
                break;
            }
            OrderAttempt attempt = attemptEntityOrder(command, context, need.entityId, RebalanceResult.TradeSide.SELL, need.needValue, new BigDecimal("999999999"), step);
            if (attempt.trade != null) {
                trades.add(attempt.trade);
                sells = sells.add(attempt.trade.notional());
                fees = fees.add(attempt.fee);
            } else if (attempt.warning != null) {
                warnings.add(attempt.warning);
            }
        }

        EntitySnapshot afterSells = entitySnapshot(context);
        BigDecimal available = context.cash.add(budget);
        List<EntityNeed> buyNeeds = underweightEntities(context, afterSells.totalValue);
        for (EntityNeed need : buyNeeds) {
            if (trades.size() >= maxOrders(command)) {
                warnings.add("Constraint: MAX_ORDERS");
                break;
            }
            OrderAttempt attempt = attemptEntityOrder(command, context, need.entityId, RebalanceResult.TradeSide.BUY, need.needValue, available, step);
            if (attempt.trade != null) {
                trades.add(attempt.trade);
                available = available.subtract(attempt.spent).max(ZERO);
                fees = fees.add(attempt.fee);
            } else if (attempt.warning != null) {
                warnings.add(attempt.warning);
            }
        }

        EntitySnapshot after = entitySnapshot(context);
        RebalanceResult.DeltaMetrics afterMetrics = weightsService.deltaMetrics(after.weights, context.targetWeights);
        List<RebalanceResult.TradeBatch> batches = trades.isEmpty() ? List.of()
                : List.of(new RebalanceResult.TradeBatch(step, scale(budget), trades, afterMetrics));
        return new Execution(context, trades, batches, warnings, fees, sells);
    }

    private List<EntityNeed> underweightEntities(PlanningContext context, BigDecimal totalForTarget) {
        Map<String, BigDecimal> diffs = entityDiffs(context, totalForTarget);
        return diffs.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .map(entry -> new EntityNeed(entry.getKey(), entry.getValue()))
                .sorted((left, right) -> right.needValue.compareTo(left.needValue))
                .toList();
    }

    private Map<String, BigDecimal> entityDiffs(PlanningContext context, BigDecimal totalForTarget) {
        Map<String, BigDecimal> values = entityValues(context);
        Map<String, BigDecimal> diffs = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : context.targetWeights.entrySet()) {
            BigDecimal targetValue = totalForTarget.multiply(entry.getValue(), MC);
            BigDecimal currentValue = values.getOrDefault(entry.getKey(), ZERO);
            diffs.put(entry.getKey(), targetValue.subtract(currentValue));
        }
        return diffs;
    }

    private void minimalStepTopUp(
            RebalanceCommand command,
            PlanningContext context,
            int step,
            List<RebalanceResult.Trade> trades,
            List<String> warnings,
            Set<String> blockedEntities,
            BigDecimal remaining
    ) {
        BigDecimal budgetLeft = remaining;
        while (budgetLeft.signum() > 0) {
            BigDecimal minimalRequired = minimalPossibleBuy(command, context);
            if (minimalRequired == null || budgetLeft.compareTo(minimalRequired) < 0) {
                break;
            }

            EntitySnapshot snapshot = entitySnapshot(context);
            Map<String, BigDecimal> diffs = entityDiffs(context, snapshot.totalValue.add(budgetLeft));
            String candidate = diffs.entrySet().stream()
                    .filter(entry -> entry.getValue().signum() > 0)
                    .filter(entry -> !blockedEntities.contains(entry.getKey()))
                    .sorted((left, right) -> right.getValue().compareTo(left.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (candidate == null) {
                break;
            }

            OrderAttempt attempt = attemptMinimalStepBuy(command, context, candidate, budgetLeft, step);
            if (attempt.trade == null) {
                blockedEntities.add(candidate);
                if (attempt.warning != null) {
                    warnings.add(attempt.warning);
                }
                if (blockedEntities.size() >= context.targetWeights.size()) {
                    break;
                }
                continue;
            }

            if (trades.size() >= maxOrders(command)) {
                warnings.add("Constraint: MAX_ORDERS");
                break;
            }
            trades.add(attempt.trade);
            budgetLeft = budgetLeft.subtract(attempt.spent).max(ZERO);
        }
    }

    private OrderAttempt attemptMinimalStepBuy(RebalanceCommand command, PlanningContext context, String entityId, BigDecimal remaining, int step) {
        ExecutionRoute route = resolveRoute(context, entityId, RebalanceResult.TradeSide.BUY);
        if (route == null) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: NO_BUY_MEMBER:" + entityId);
        }
        AssetState asset = context.assets.get(route.assetId);
        if (asset == null || asset.price == null || asset.price.signum() <= 0) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: MISSING_PRICE:" + route.assetId);
        }

        BigDecimal stepQty = minimalQuantity(command, asset);
        if (stepQty.signum() <= 0) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: SIZING:" + route.assetId);
        }

        BigDecimal notional = stepQty.multiply(asset.price);
        if (!sizingModel.isFeasible(stepQty, asset.price, command.sizing())) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: SIZING:" + route.assetId);
        }
        BigDecimal fee = feeModel.feeForOrder(notional, command.fees());
        if (notional.add(fee).compareTo(remaining) > 0) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: BUDGET");
        }

        RebalanceResult.Trade trade = applyTrade(context, route, RebalanceResult.TradeSide.BUY, stepQty, notional, fee, step);
        return new OrderAttempt(trade, notional.add(fee), fee, null);
    }

    private BigDecimal minimalPossibleBuy(RebalanceCommand command, PlanningContext context) {
        BigDecimal best = null;
        for (String entityId : context.targetWeights.keySet()) {
            ExecutionRoute route = resolveRoute(context, entityId, RebalanceResult.TradeSide.BUY);
            if (route == null) {
                continue;
            }
            AssetState asset = context.assets.get(route.assetId);
            if (asset == null || asset.price == null || asset.price.signum() <= 0) {
                continue;
            }
            BigDecimal qty = minimalQuantity(command, asset);
            if (qty.signum() <= 0) {
                continue;
            }
            if (!sizingModel.isFeasible(qty, asset.price, command.sizing())) {
                continue;
            }
            BigDecimal total = qty.multiply(asset.price).add(feeModel.feeForOrder(qty.multiply(asset.price), command.fees()));
            best = best == null ? total : best.min(total);
        }
        return best;
    }

    private BigDecimal minimalQuantity(RebalanceCommand command, AssetState asset) {
        BigDecimal minQty = command.sizing() != null && command.sizing().minQuantity() != null ? command.sizing().minQuantity() : ZERO;
        BigDecimal step = command.sizing() != null && command.sizing().quantityStep() != null && command.sizing().quantityStep().signum() > 0
                ? command.sizing().quantityStep()
                : (command.sizing() != null && Boolean.FALSE.equals(command.sizing().fractionalAllowed()) ? ONE : new BigDecimal("0.0001"));
        BigDecimal candidate = minQty.max(step);
        return sizingModel.roundQuantity(candidate, command.sizing(), command.options() == null ? null : command.options().roundingMode());
    }

    private OrderAttempt attemptEntityOrder(
            RebalanceCommand command,
            PlanningContext context,
            String entityId,
            RebalanceResult.TradeSide side,
            BigDecimal desiredNotional,
            BigDecimal remainingBudget,
            int step
    ) {
        ExecutionRoute route = resolveRoute(context, entityId, side);
        if (route == null) {
            return new OrderAttempt(null, ZERO, ZERO,
                    "Constraint: " + (side == RebalanceResult.TradeSide.BUY ? "NO_BUY_MEMBER:" : "NO_SELL_MEMBER:") + entityId);
        }

        AssetState asset = context.assets.get(route.assetId);
        if (asset == null || asset.price == null || asset.price.signum() <= 0) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: MISSING_PRICE:" + route.assetId);
        }

        BigDecimal cappedNotional = applyOrderCaps(command, desiredNotional.abs());
        if (cappedNotional.signum() <= 0) {
            return new OrderAttempt(null, ZERO, ZERO, "Constraint: ORDER_CAP:" + route.assetId);
        }

        BigDecimal quantity = sizedQuantity(command, asset, cappedNotional, side);
        while (quantity.signum() > 0) {
            BigDecimal notional = quantity.multiply(asset.price);
            BigDecimal fee = feeModel.feeForOrder(notional, command.fees());
            boolean feasible = sizingModel.isFeasible(quantity, asset.price, command.sizing());
            if (side == RebalanceResult.TradeSide.BUY) {
                feasible = feasible && notional.add(fee).compareTo(remainingBudget) <= 0;
            }
            if (feasible) {
                RebalanceResult.Trade trade = applyTrade(context, route, side, quantity, notional, fee, step);
                BigDecimal spent = side == RebalanceResult.TradeSide.BUY ? notional.add(fee) : ZERO;
                return new OrderAttempt(trade, spent, fee, null);
            }
            quantity = decrementQuantity(command, quantity);
        }

        return new OrderAttempt(null, ZERO, ZERO, "Constraint: " + (side == RebalanceResult.TradeSide.BUY ? "BUDGET" : "SIZING") + ":" + route.assetId);
    }

    private BigDecimal applyOrderCaps(RebalanceCommand command, BigDecimal notional) {
        BigDecimal capped = notional;
        if (command.constraints() != null && command.constraints().maxNotionalPerOrder() != null) {
            capped = capped.min(command.constraints().maxNotionalPerOrder());
        }
        if (command.constraints() != null && command.constraints().risk() != null && command.constraints().risk().maxSingleTradeNotional() != null) {
            capped = capped.min(command.constraints().risk().maxSingleTradeNotional());
        }
        if (command.constraints() != null && command.constraints().maxNotionalPerAsset() != null) {
            capped = capped.min(command.constraints().maxNotionalPerAsset());
        }
        return capped;
    }

    private BigDecimal sizedQuantity(RebalanceCommand command, AssetState asset, BigDecimal notional, RebalanceResult.TradeSide side) {
        BigDecimal raw = notional.divide(asset.price, 12, RoundingMode.DOWN);
        BigDecimal quantity = sizingModel.roundQuantity(raw, command.sizing(), command.options() == null ? null : command.options().roundingMode());
        return side == RebalanceResult.TradeSide.SELL ? quantity.min(asset.quantity) : quantity;
    }

    private BigDecimal decrementQuantity(RebalanceCommand command, BigDecimal current) {
        BigDecimal step = command.sizing() != null && command.sizing().quantityStep() != null && command.sizing().quantityStep().signum() > 0
                ? command.sizing().quantityStep()
                : (command.sizing() != null && Boolean.FALSE.equals(command.sizing().fractionalAllowed()) ? ONE : new BigDecimal("0.0001"));
        BigDecimal next = current.subtract(step);
        if (next.signum() <= 0) {
            return ZERO;
        }
        return sizingModel.roundQuantity(next, command.sizing(), command.options() == null ? null : command.options().roundingMode());
    }

    private PlanningContext createContext(RebalanceCommand command) {
        Map<String, AssetState> assets = new LinkedHashMap<>();
        Set<String> ignoredAssetIds = ignoredAssetIds(command);
        if (command.portfolio().positions() != null) {
            for (RebalanceCommand.Position position : command.portfolio().positions()) {
                if (position == null) {
                    continue;
                }
                String assetId = position.assetId() == null ? position.symbol() : position.assetId();
                if (assetId == null) {
                    continue;
                }
                assets.put(assetId, new AssetState(
                        assetId,
                        position.symbol(),
                        valueOrZero(position.quantity()),
                        position.price(),
                        flag(position.allowTrade(), true),
                        flag(position.allowBuy(), true),
                        flag(position.allowSell(), true)
                ));
            }
        }

        if (command.targets() != null) {
            for (RebalanceCommand.TargetAllocation target : command.targets()) {
                if (target != null && target.assetId() != null) {
                    assets.putIfAbsent(target.assetId(), new AssetState(target.assetId(), target.assetId(), ZERO, null, true, true, false));
                }
            }
        }

        if (command.composites() != null) {
            for (RebalanceCommand.Composite composite : command.composites()) {
                if (composite == null || composite.members() == null) {
                    continue;
                }
                for (String member : composite.members()) {
                    if (member != null) {
                        assets.putIfAbsent(member, new AssetState(member, member, ZERO, null, true, true, false));
                    }
                }
            }
        }

        Map<String, CompositeSpec> composites = new LinkedHashMap<>();
        Map<String, String> memberToComposite = new HashMap<>();
        if (command.composites() != null) {
            for (RebalanceCommand.Composite composite : command.composites()) {
                if (composite == null || composite.compositeId() == null) {
                    continue;
                }
                composites.put(composite.compositeId(), new CompositeSpec(
                        composite.compositeId(),
                        composite.members() == null ? List.of() : composite.members(),
                        composite.tradePolicy()
                ));
                if (composite.members() != null) {
                    for (String member : composite.members()) {
                        if (member != null) {
                            memberToComposite.put(member, composite.compositeId());
                        }
                    }
                }
            }
        }

        Map<String, BigDecimal> targetWeights = targetWeights(command, memberToComposite);
        BigDecimal cash = command.portfolio().cash() == null ? ZERO : valueOrZero(command.portfolio().cash().amount());
        boolean allowExtraAssets = command.options() != null && Boolean.TRUE.equals(command.options().allowExtraAssets());
        return new PlanningContext(assets, composites, memberToComposite, targetWeights, cash, allowExtraAssets, ignoredAssetIds);
    }

    private Map<String, BigDecimal> targetWeights(RebalanceCommand command, Map<String, String> memberToComposite) {
        boolean allowMemberTargets = command.options() != null && Boolean.TRUE.equals(command.options().allowMemberTargets());
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        if (command.targets() != null) {
            for (RebalanceCommand.TargetAllocation target : command.targets()) {
                if (target == null || target.assetId() == null || target.targetWeight() == null) {
                    continue;
                }
                if (!allowMemberTargets && memberToComposite.containsKey(target.assetId())) {
                    continue;
                }
                weights.put(target.assetId(), target.targetWeight());
            }
        }
        if (command.composites() != null) {
            for (RebalanceCommand.Composite composite : command.composites()) {
                if (composite != null && composite.compositeId() != null && composite.targetWeight() != null) {
                    weights.put(composite.compositeId(), composite.targetWeight());
                }
            }
        }
        return weights;
    }

    private EntitySnapshot entitySnapshot(PlanningContext context) {
        BigDecimal totalValue = context.cash;
        for (AssetState asset : context.assets.values()) {
            if (ignoredAsset(context, asset.assetId)) {
                continue;
            }
            totalValue = totalValue.add(asset.marketValue());
        }

        Map<String, BigDecimal> values = entityValues(context);

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            weights.put(entry.getKey(), totalValue.signum() == 0 ? ZERO : entry.getValue().divide(totalValue, 12, RoundingMode.HALF_UP));
        }
        return new EntitySnapshot(totalValue, weights, values);
    }

    private Map<String, BigDecimal> entityValues(PlanningContext context) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String entityId : context.targetWeights.keySet()) {
            if (context.composites.containsKey(entityId)) {
                BigDecimal total = ZERO;
                for (String member : context.composites.get(entityId).members) {
                    if (ignoredAsset(context, member)) {
                        continue;
                    }
                    AssetState asset = context.assets.get(member);
                    if (asset != null) {
                        total = total.add(asset.marketValue());
                    }
                }
                values.put(entityId, total);
            } else {
                if (ignoredAsset(context, entityId)) {
                    continue;
                }
                AssetState asset = context.assets.get(entityId);
                values.put(entityId, asset == null ? ZERO : asset.marketValue());
            }
        }
        if (!allowExtraAssets(context)) {
            for (Map.Entry<String, AssetState> entry : context.assets.entrySet()) {
                String assetId = entry.getKey();
                if (ignoredAsset(context, assetId)) {
                    continue;
                }
                if (!context.targetWeights.containsKey(assetId) && !context.memberToComposite.containsKey(assetId)) {
                    values.putIfAbsent(assetId, entry.getValue().marketValue());
                }
            }
        }
        return values;
    }

    private ExecutionRoute resolveRoute(PlanningContext context, String entityId, RebalanceResult.TradeSide side) {
        if (!context.composites.containsKey(entityId)) {
            AssetState asset = context.assets.get(entityId);
            if (asset == null) {
                return null;
            }
            if (ignoredAsset(context, entityId)) {
                return null;
            }
            if (!asset.allowTrade) {
                return null;
            }
            if (side == RebalanceResult.TradeSide.BUY && !asset.allowBuy) {
                return null;
            }
            if (side == RebalanceResult.TradeSide.SELL && !asset.allowSell) {
                return null;
            }
            return new ExecutionRoute(entityId, null);
        }

        CompositeSpec composite = context.composites.get(entityId);
        List<String> candidates = side == RebalanceResult.TradeSide.BUY
                ? composite.buyMembers()
                : composite.sellMembers();
        for (String member : candidates) {
            if (ignoredAsset(context, member)) {
                continue;
            }
            AssetState asset = context.assets.get(member);
            if (asset == null) {
                continue;
            }
            if (!asset.allowTrade) {
                continue;
            }
            if (side == RebalanceResult.TradeSide.BUY && asset.allowBuy) {
                return new ExecutionRoute(member, entityId);
            }
            if (side == RebalanceResult.TradeSide.SELL && asset.allowSell) {
                return new ExecutionRoute(member, entityId);
            }
        }
        return null;
    }

    private RebalanceResult.Trade applyTrade(
            PlanningContext context,
            ExecutionRoute route,
            RebalanceResult.TradeSide side,
            BigDecimal quantity,
            BigDecimal notional,
            BigDecimal fee,
            int step
    ) {
        AssetState asset = context.assets.get(route.assetId);
        BigDecimal nextQuantity = side == RebalanceResult.TradeSide.BUY
                ? asset.quantity.add(quantity)
                : asset.quantity.subtract(quantity).max(ZERO);
        context.assets.put(route.assetId, new AssetState(
                asset.assetId,
                asset.symbol,
                nextQuantity,
                asset.price,
                asset.allowTrade,
                asset.allowBuy,
                asset.allowSell
        ));
        context.cash = side == RebalanceResult.TradeSide.BUY
                ? context.cash.subtract(notional).subtract(fee)
                : context.cash.add(notional).subtract(fee);
        return new RebalanceResult.Trade(step, route.assetId, side, scale(quantity), scale(notional), scale(fee), route.compositeId);
    }

    private AdditionalCash additionalCash(RebalanceCommand command, PlanningContext baseContext) {
        if (!isBuyOnly(command.policy())) {
            return new AdditionalCash(null, ZERO);
        }

        if (reachesExactTarget(command, baseContext.copy(), budget(command))) {
            return new AdditionalCash(ZERO, ZERO);
        }

        BigDecimal low = ZERO;
        BigDecimal high = EXTRA_CASH_CAP;
        BigDecimal found = null;

        for (int i = 0; i < EXTRA_CASH_ITERATIONS; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            boolean exact = reachesExactTarget(command, baseContext.copy(), budget(command).add(mid));
            if (exact) {
                found = mid;
                high = mid;
            } else {
                low = mid;
            }
        }

        return new AdditionalCash(found, low.setScale(8, RoundingMode.HALF_UP));
    }

    private ExactTargetCashGap exactTargetCashGap(RebalanceCommand command, PlanningContext context) {
        BigDecimal gap = exactTargetCashGapForContext(command, context);
        return new ExactTargetCashGap(gap, gap);
    }

    private TargetThresholdCashGap targetThresholdCashGap(RebalanceCommand command, PlanningContext context) {
        BigDecimal gap = targetThresholdCashGapForContext(command, context);
        return new TargetThresholdCashGap(gap, gap);
    }

    private TargetThresholdCashGap targetThresholdCashGap(RebalanceCommand command, PlanningContext beforeContext, PlanningContext afterContext) {
        return new TargetThresholdCashGap(
                targetThresholdCashGapForContext(command, beforeContext),
                targetThresholdCashGapForContext(command, afterContext)
        );
    }

    private ExactTargetCashGap exactTargetCashGap(RebalanceCommand command, PlanningContext beforeContext, PlanningContext afterContext) {
        return new ExactTargetCashGap(
                exactTargetCashGapForContext(command, beforeContext),
                exactTargetCashGapForContext(command, afterContext)
        );
    }

    private BigDecimal targetThresholdCashGapForContext(RebalanceCommand command, PlanningContext context) {
        if (!isBuyOnly(command.policy())) {
            return null;
        }
        if (command.threshold() == null || command.threshold().type() == null || command.threshold().value() == null) {
            return exactTargetCashGapForContext(command, context);
        }

        RebalanceCommand searchCommand = thresholdCashGapSearchCommand(command);
        if (reachesConfiguredThreshold(searchCommand, context.copy(), ZERO)) {
            return ZERO;
        }

        BigDecimal low = ZERO;
        BigDecimal high = EXTRA_CASH_CAP;
        BigDecimal found = null;

        for (int i = 0; i < EXTRA_CASH_ITERATIONS; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            boolean thresholdReached = reachesConfiguredThreshold(searchCommand, context.copy(), mid);
            if (thresholdReached) {
                found = mid;
                high = mid;
            } else {
                low = mid;
            }
        }
        return scale(found != null ? found : low.setScale(8, RoundingMode.HALF_UP));
    }

    private BigDecimal exactTargetCashGapForContext(RebalanceCommand command, PlanningContext context) {
        if (!isBuyOnly(command.policy())) {
            return null;
        }

        RebalanceCommand searchCommand = thresholdCashGapSearchCommand(command);
        if (reachesExactTarget(searchCommand, context.copy(), ZERO)) {
            return ZERO;
        }

        BigDecimal low = ZERO;
        BigDecimal high = EXTRA_CASH_CAP;
        BigDecimal found = null;

        for (int i = 0; i < EXTRA_CASH_ITERATIONS; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            boolean exact = reachesExactTarget(searchCommand, context.copy(), mid);
            if (exact) {
                found = mid;
                high = mid;
            } else {
                low = mid;
            }
        }
        return scale(found != null ? found : low.setScale(8, RoundingMode.HALF_UP));
    }

    private RebalanceCommand thresholdCashGapSearchCommand(RebalanceCommand command) {
        return new RebalanceCommand(
                command.requestId(),
                command.asOf(),
                command.baseCurrency(),
                command.policy(),
                command.portfolio(),
                command.targets(),
                command.composites(),
                new RebalanceCommand.Budget(RebalanceCommand.BudgetMode.NEW_CASH_ONLY, ZERO, command.baseCurrency()),
                command.plan(),
                command.threshold(),
                command.fees(),
                command.sizing(),
                command.constraints(),
                command.options()
        );
    }

    private boolean reachesExactTarget(RebalanceCommand command, PlanningContext context, BigDecimal budgetAmount) {
        Execution execution = buyOnlyExecution(command, context, budgetAmount, 1);
        EntitySnapshot snapshot = entitySnapshot(execution.context);
        RebalanceResult.DeltaMetrics metrics = weightsService.deltaMetrics(snapshot.weights, execution.context.targetWeights);
        BigDecimal tolerance = command.threshold() != null && command.threshold().value() != null
                ? command.threshold().value()
                : new BigDecimal("0.000001");
        return metrics.maxAbs().compareTo(tolerance) <= 0 || metrics.l2().compareTo(tolerance) <= 0;
    }

    private boolean reachesConfiguredThreshold(RebalanceCommand command, PlanningContext context, BigDecimal budgetAmount) {
        Execution execution = buyOnlyExecution(command, context, budgetAmount, 1);
        EntitySnapshot snapshot = entitySnapshot(execution.context);
        RebalanceResult.DeltaMetrics metrics = weightsService.deltaMetrics(snapshot.weights, execution.context.targetWeights);
        return weightsService.withinThreshold(metrics, command.threshold(), snapshot.totalValue);
    }

    private RebalanceResult buildResult(
            RebalanceCommand command,
            RebalanceResult.Status status,
            String reason,
            EntitySnapshot snapshot,
            RebalanceResult.DeltaMetrics before,
            RebalanceResult.DeltaMetrics after,
            List<RebalanceResult.Trade> trades,
            List<RebalanceResult.TradeBatch> batches,
            List<String> warnings,
            boolean normalized,
            BigDecimal targetThresholdCashGapBefore,
            BigDecimal targetThresholdCashGapAfter,
            BigDecimal exactTargetCashGapBefore,
            BigDecimal exactTargetCashGapAfter,
            BigDecimal fees,
            BigDecimal sellNotional,
            BigDecimal additionalExact,
            BigDecimal additionalLower
    ) {
        List<String> bindingConstraints = warnings.stream()
                .filter(value -> value.startsWith("Constraint:"))
                .map(value -> value.substring("Constraint:".length()).trim())
                .distinct()
                .toList();

        return new RebalanceResult(
                command.requestId() == null ? UUID.randomUUID() : command.requestId(),
                command.policy(),
                status,
                reason,
                new RebalanceResult.Metrics(
                        before,
                        after,
                        scale(targetThresholdCashGapBefore),
                        scale(targetThresholdCashGapAfter),
                        scale(exactTargetCashGapBefore),
                        scale(exactTargetCashGapAfter),
                        scale(fees),
                        new RebalanceResult.Turnover(scale(buyNotional(trades)), scale(sellNotional)),
                        scale(additionalExact),
                        scale(additionalLower),
                        bindingConstraints
                ),
                new RebalanceResult.Current(
                        scale(snapshot.totalValue),
                        snapshot.weights.entrySet().stream().collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> scale(entry.getValue()),
                                (left, right) -> left,
                                LinkedHashMap::new
                        ))
                ),
                new RebalanceResult.Recommended(trades),
                batches,
                new RebalanceResult.Debug(warnings, normalized)
        );
    }

    private RebalanceResult error(RebalanceCommand command, RebalanceResult.Status status, String reason) {
        return new RebalanceResult(
                command == null || command.requestId() == null ? UUID.randomUUID() : command.requestId(),
                command == null ? null : command.policy(),
                status,
                reason,
                null,
                null,
                new RebalanceResult.Recommended(List.of()),
                List.of(),
                new RebalanceResult.Debug(List.of(), false)
        );
    }

    private Normalized normalize(RebalanceCommand command) {
        BigDecimal sum = targetWeights(command, createMemberToComposite(command)).values().stream().reduce(ZERO, BigDecimal::add);
        BigDecimal epsilon = command.options() != null && command.options().epsilonWeightSum() != null
                ? command.options().epsilonWeightSum()
                : new BigDecimal("0.000001");
        if (sum.signum() <= 0 || sum.subtract(ONE).abs().compareTo(epsilon) <= 0) {
            return new Normalized(command, false, List.of());
        }

        List<RebalanceCommand.TargetAllocation> normalizedTargets = new ArrayList<>();
        List<RebalanceCommand.Composite> normalizedComposites = new ArrayList<>();
        boolean allowMemberTargets = command.options() != null && Boolean.TRUE.equals(command.options().allowMemberTargets());
        Set<String> compositeIds = command.composites() == null ? Set.of()
                : command.composites().stream().filter(Objects::nonNull).map(RebalanceCommand.Composite::compositeId).filter(Objects::nonNull).collect(Collectors.toSet());

        if (command.targets() != null) {
            for (RebalanceCommand.TargetAllocation target : command.targets()) {
                if (target == null || target.assetId() == null || target.targetWeight() == null) {
                    continue;
                }
                if (!allowMemberTargets && createMemberToComposite(command).containsKey(target.assetId())) {
                    normalizedTargets.add(target);
                    continue;
                }
                if (compositeIds.contains(target.assetId())) {
                    normalizedTargets.add(target);
                    continue;
                }
                normalizedTargets.add(new RebalanceCommand.TargetAllocation(target.assetId(), target.targetWeight().divide(sum, 12, RoundingMode.HALF_UP)));
            }
        }
        if (command.composites() != null) {
            for (RebalanceCommand.Composite composite : command.composites()) {
                if (composite == null || composite.targetWeight() == null) {
                    normalizedComposites.add(composite);
                    continue;
                }
                normalizedComposites.add(new RebalanceCommand.Composite(
                        composite.compositeId(),
                        composite.targetWeight().divide(sum, 12, RoundingMode.HALF_UP),
                        composite.members(),
                        composite.tradePolicy()
                ));
            }
        }

        RebalanceCommand normalized = new RebalanceCommand(
                command.requestId(),
                command.asOf(),
                command.baseCurrency(),
                command.policy(),
                command.portfolio(),
                normalizedTargets,
                normalizedComposites,
                command.budget(),
                command.plan(),
                command.threshold(),
                command.fees(),
                command.sizing(),
                command.constraints(),
                command.options()
        );
        return new Normalized(normalized, true, List.of("Target weights renormalized due to epsilon mismatch"));
    }

    private Validation validate(RebalanceCommand command) {
        if (command == null) {
            return new Validation(false, "Command is required");
        }
        if (command.portfolio() == null) {
            return new Validation(false, "Portfolio is required");
        }
        Set<String> seenMembers = new LinkedHashSet<>();
        if (command.composites() != null) {
            for (RebalanceCommand.Composite composite : command.composites()) {
                if (composite == null || composite.members() == null) {
                    continue;
                }
                for (String member : composite.members()) {
                    if (member != null && !seenMembers.add(member)) {
                        return new Validation(false, "Composite member appears in multiple composites: " + member);
                    }
                }
            }
        }
        if (command.portfolio().positions() != null) {
            for (RebalanceCommand.Position position : command.portfolio().positions()) {
                if (position != null && position.price() != null && position.price().signum() < 0) {
                    return new Validation(false, "Negative prices are not allowed");
                }
                if (position != null && position.quantity() != null && position.quantity().signum() < 0) {
                    return new Validation(false, "Negative quantities are not allowed");
                }
            }
        }
        return new Validation(true, null);
    }

    private RebalanceResult.Status deriveStatus(Execution execution, RebalanceResult.DeltaMetrics before, RebalanceResult.DeltaMetrics after) {
        if (execution.trades.isEmpty()) {
            return RebalanceResult.Status.INFEASIBLE;
        }
        if (after.l2().compareTo(before.l2()) > 0 || execution.warnings.stream().anyMatch(text -> text.startsWith("Constraint:"))) {
            return RebalanceResult.Status.PARTIAL;
        }
        return RebalanceResult.Status.OK;
    }

    private boolean killSwitch(RebalanceCommand command) {
        return command != null && command.constraints() != null && command.constraints().risk() != null
                && Boolean.TRUE.equals(command.constraints().risk().killSwitch());
    }

    private boolean isBuyOnly(RebalanceCommand.Policy policy) {
        return policy == RebalanceCommand.Policy.BUY_ONLY || policy == RebalanceCommand.Policy.BUY_ONLY_PLAN_N;
    }

    private boolean allowExtraAssets(PlanningContext context) {
        return context.allowExtraAssets;
    }

    private Set<String> ignoredAssetIds(RebalanceCommand command) {
        if (command == null || command.options() == null || command.options().ignoredAssetIds() == null) {
            return Set.of();
        }
        return command.options().ignoredAssetIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean ignoredAsset(PlanningContext context, String assetId) {
        return assetId != null && context.ignoredAssetIds.contains(assetId);
    }

    private Map<String, String> createMemberToComposite(RebalanceCommand command) {
        Map<String, String> memberToComposite = new HashMap<>();
        if (command.composites() == null) {
            return memberToComposite;
        }
        for (RebalanceCommand.Composite composite : command.composites()) {
            if (composite == null || composite.compositeId() == null || composite.members() == null) {
                continue;
            }
            for (String member : composite.members()) {
                if (member != null) {
                    memberToComposite.put(member, composite.compositeId());
                }
            }
        }
        return memberToComposite;
    }

    private BigDecimal budget(RebalanceCommand command) {
        return command.budget() == null || command.budget().amount() == null ? ZERO : command.budget().amount();
    }

    private int maxOrders(RebalanceCommand command) {
        return command.constraints() != null && command.constraints().maxOrders() != null
                ? command.constraints().maxOrders()
                : Integer.MAX_VALUE;
    }

    private BigDecimal buyNotional(List<RebalanceResult.Trade> trades) {
        return trades.stream()
                .filter(trade -> trade.side() == RebalanceResult.TradeSide.BUY)
                .map(RebalanceResult.Trade::notional)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
    }

    private List<String> merge(Collection<String> left, Collection<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged.stream().distinct().toList();
    }

    private boolean flag(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private record Validation(boolean valid, String reason) {
    }

    private record Normalized(RebalanceCommand command, boolean normalized, List<String> warnings) {
    }

    private record AdditionalCash(BigDecimal exact, BigDecimal lowerBound) {
    }

    private record TargetThresholdCashGap(BigDecimal before, BigDecimal after) {
    }

    private record ExactTargetCashGap(BigDecimal before, BigDecimal after) {
    }

    private record EntityNeed(String entityId, BigDecimal needValue) {
    }

    private record ExecutionRoute(String assetId, String compositeId) {
    }

    private record OrderAttempt(RebalanceResult.Trade trade, BigDecimal spent, BigDecimal fee, String warning) {
    }

    private record EntitySnapshot(BigDecimal totalValue, Map<String, BigDecimal> weights, Map<String, BigDecimal> values) {
    }

    private record Execution(
            PlanningContext context,
            List<RebalanceResult.Trade> trades,
            List<RebalanceResult.TradeBatch> batches,
            List<String> warnings,
            BigDecimal fees,
            BigDecimal sellNotional
    ) {
    }

    private static final class AssetState {
        private final String assetId;
        private final String symbol;
        private final BigDecimal quantity;
        private final BigDecimal price;
        private final boolean allowTrade;
        private final boolean allowBuy;
        private final boolean allowSell;

        private AssetState(String assetId, String symbol, BigDecimal quantity, BigDecimal price, boolean allowTrade, boolean allowBuy, boolean allowSell) {
            this.assetId = assetId;
            this.symbol = symbol;
            this.quantity = quantity;
            this.price = price;
            this.allowTrade = allowTrade;
            this.allowBuy = allowBuy;
            this.allowSell = allowSell;
        }

        private BigDecimal marketValue() {
            if (price == null || quantity == null) {
                return ZERO;
            }
            return quantity.multiply(price);
        }
    }

    private static final class CompositeSpec {
        private final String compositeId;
        private final List<String> members;
        private final RebalanceCommand.TradePolicy tradePolicy;

        private CompositeSpec(String compositeId, List<String> members, RebalanceCommand.TradePolicy tradePolicy) {
            this.compositeId = compositeId;
            this.members = members == null ? List.of() : members;
            this.tradePolicy = tradePolicy;
        }

        private List<String> buyMembers() {
            if (tradePolicy != null && tradePolicy.buyMembers() != null && !tradePolicy.buyMembers().isEmpty()) {
                return tradableMembers(tradePolicy.buyMembers());
            }
            return List.of();
        }

        private List<String> sellMembers() {
            if (tradePolicy != null && tradePolicy.sellMembers() != null && !tradePolicy.sellMembers().isEmpty()) {
                return tradableMembers(tradePolicy.sellMembers());
            }
            return List.of();
        }

        private List<String> tradableMembers(List<String> candidates) {
            Set<String> locked = tradePolicy != null && tradePolicy.lockedMembers() != null
                    ? new LinkedHashSet<>(tradePolicy.lockedMembers())
                    : Set.of();
            return candidates.stream().filter(Objects::nonNull).filter(member -> !locked.contains(member)).toList();
        }
    }

    private static final class PlanningContext {
        private final Map<String, AssetState> assets;
        private final Map<String, CompositeSpec> composites;
        private final Map<String, String> memberToComposite;
        private final Map<String, BigDecimal> targetWeights;
        private final boolean allowExtraAssets;
        private final Set<String> ignoredAssetIds;
        private BigDecimal cash;

        private PlanningContext(
                Map<String, AssetState> assets,
                Map<String, CompositeSpec> composites,
                Map<String, String> memberToComposite,
                Map<String, BigDecimal> targetWeights,
                BigDecimal cash,
                boolean allowExtraAssets,
                Set<String> ignoredAssetIds
        ) {
            this.assets = assets;
            this.composites = composites;
            this.memberToComposite = memberToComposite;
            this.targetWeights = targetWeights;
            this.cash = cash;
            this.allowExtraAssets = allowExtraAssets;
            this.ignoredAssetIds = ignoredAssetIds;
        }

        private PlanningContext copy() {
            Map<String, AssetState> assetCopy = new LinkedHashMap<>();
            for (Map.Entry<String, AssetState> entry : assets.entrySet()) {
                AssetState asset = entry.getValue();
                assetCopy.put(entry.getKey(), new AssetState(
                        asset.assetId,
                        asset.symbol,
                        asset.quantity,
                        asset.price,
                        asset.allowTrade,
                        asset.allowBuy,
                        asset.allowSell
                ));
            }
            return new PlanningContext(assetCopy, composites, memberToComposite, targetWeights, cash, allowExtraAssets, ignoredAssetIds);
        }
    }

}
