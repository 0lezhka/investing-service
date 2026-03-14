# Rebalancing Service Use Cases Based on Current Code

This file summarizes the main use cases supported by the current implementation in `src/main/java/com/investing/service/investingservice/rebalancing`.

It is intentionally narrower than `rebalancing_service_context_v2.md` and includes only behavior that is implemented in code today.

## What the code actually supports

### CLI summary reporting for `rebalance run` and `rebalance validate`

The CLI summary mode is a human-readable terminal report.

Supported behavior:
- `--output summary` remains the default for `run` and `validate`
- summary output uses section headers, status banners, aligned ASCII tables, and grouped diagnostics
- `run` summary includes execution context, portfolio/plan settings, metrics before vs after, trade tables, step breakdown, and warnings
- the metrics table now includes a buy-only `Cash Gap` row showing the external cash needed to satisfy the configured threshold before and after the rebalance
- the summary does not show `Exact Cash Gap`; that metric remains internal/structured only
- `validate` summary includes request context and a clear validation result block
- ANSI color may be used automatically for interactive terminals, but the output remains readable as plain text when color is unavailable or disabled

### CLI live-price refresh for `rebalance run`

The CLI can optionally refresh current portfolio prices from the market-data use case before planning.

Supported behavior:
- `rebalance run --load-current-prices` fetches current prices for each portfolio position
- ticker lookup uses `position.symbol`, falling back to `position.assetId`
- fetched `currentPrice` values replace the input `price` only for that CLI execution
- the run fails if any position price cannot be refreshed, so mixed live and stale input prices are not used

### 1. Return `NO_TRADES` when the portfolio is already within threshold

The engine calculates current weights and delta metrics, then stops without producing trades when the configured threshold is met.

Supported threshold types:
- `MAX_ABS`
- `L1`
- `VALUE`

Example:
```json
{
  "policy": "BUY_ONLY",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "VOO", "quantity": 6, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true },
      { "assetId": "BND", "quantity": 4, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.60 },
    { "assetId": "BND", "targetWeight": 0.40 }
  ],
  "threshold": { "type": "MAX_ABS", "value": 0.01 }
}
```

Expected behavior:
- status is `NO_TRADES`
- `recommended.trades` is empty
- `metrics.before` and `metrics.after` are equal

### 2. Return `NO_TRADES` when the risk kill switch is enabled

If `constraints.risk.killSwitch = true`, the engine exits immediately and reports `NO_TRADES`.

Example:
```json
{
  "policy": "CLASSIC_FULL_REBALANCE",
  "constraints": {
    "risk": { "killSwitch": true }
  }
}
```

Expected behavior:
- status is `NO_TRADES`
- reason is `Kill switch enabled`
- `bindingConstraints` contains `KILL_SWITCH`

### 3. Single-step buy-only rebalance using a cash budget

For `BUY_ONLY`, the engine:
- computes underweight target entities
- allocates the budget proportionally
- rounds quantity using sizing rules
- applies fees
- optionally uses leftover cash for minimal extra buy steps

Example:
```json
{
  "policy": "BUY_ONLY",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "VOO", "quantity": 8, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.50 },
    { "assetId": "BND", "targetWeight": 0.50 }
  ],
  "budget": { "amount": 200, "currency": "USD" },
  "fees": { "perOrderFixed": 1.00, "percentOfNotional": 0.0000, "minFee": 1.00 },
  "sizing": { "fractionalAllowed": false, "quantityStep": 1, "minNotional": 1, "minQuantity": 1 },
  "options": { "roundingMode": "FLOOR" }
}
```

Expected behavior:
- only `BUY` trades are produced
- the engine can buy `BND` even if it is not currently held
- total spending includes fees and does not exceed budget
- for `NEW_CASH_ONLY`, post-trade portfolio value and after-metrics are evaluated on the funded portfolio, including the newly added cash budget

### 4. Multi-step buy-only plan with equal step budgets and carry-over

For `BUY_ONLY_PLAN_N`, the engine splits the budget into equal steps, runs buy-only planning for each step, and carries unused money into the next step.

Example:
```json
{
  "policy": "BUY_ONLY_PLAN_N",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "VOO", "quantity": 5, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.50 },
    { "assetId": "BND", "targetWeight": 0.50 }
  ],
  "budget": { "amount": 300, "currency": "USD" },
  "plan": { "steps": 3, "stepBudgetMode": "EQUAL", "spendMode": "UP_TO_BUDGET" },
  "sizing": { "fractionalAllowed": false, "quantityStep": 1, "minNotional": 100, "minQuantity": 1 }
}
```

Expected behavior:
- result may contain up to 3 `steps`
- each step contains its own `trades`
- if one step cannot spend all its budget, the remainder is carried into the next step
- planning can stop early if no further feasible trades exist

### 5. Full rebalance with sells first, then buys

For all non-buy-only policies, the current engine uses the same full-rebalance path:
- sell overweight entities first
- then buy underweight entities using available cash

This applies to:
- `CLASSIC_FULL_REBALANCE`
- `CONSTRAINED_OPTIMIZATION`
- `TAX_AWARE`

Example:
```json
{
  "policy": "CLASSIC_FULL_REBALANCE",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "VOO", "quantity": 9, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true },
      { "assetId": "BND", "quantity": 1, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.50 },
    { "assetId": "BND", "targetWeight": 0.50 }
  ]
}
```

Expected behavior:
- engine may produce a `SELL` for `VOO`
- engine may then produce a `BUY` for `BND`
- result status is `OK` or `PARTIAL` depending on constraints and improvement

### 6. Composite target treated as one allocation bucket

The engine can aggregate several tickers into a composite target entity and route trades through allowed composite members.

Implemented composite behavior:
- composite weight is based on the sum of member market values
- composite target weight participates in delta calculation
- buy routing uses `tradePolicy.buyMembers`
- sell routing uses `tradePolicy.sellMembers`
- locked members are excluded from those candidate lists

Example:
```json
{
  "policy": "BUY_ONLY",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "AAA", "quantity": 10, "price": 100, "allowTrade": true, "allowBuy": false, "allowSell": false },
      { "assetId": "CCC", "quantity": 1, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.70 }
  ],
  "composites": [
    {
      "compositeId": "LEGACY_TECH",
      "targetWeight": 0.30,
      "members": ["AAA", "CCC"],
      "tradePolicy": {
        "buyMembers": ["CCC"],
        "sellMembers": [],
        "lockedMembers": ["AAA"]
      }
    }
  ],
  "budget": { "amount": 100, "currency": "USD" }
}
```

Expected behavior:
- current composite value is `AAA + CCC`
- if the composite needs a buy, the trade is placed on `CCC`
- the produced trade includes `compositeId = LEGACY_TECH`

### 7. Block trades when asset or composite routing does not allow them

The engine can return `PARTIAL` or `INFEASIBLE` when trades are blocked by routing or permissions.

Examples of implemented blockers:
- asset has `allowTrade = false`
- buy requested but `allowBuy = false`
- sell requested but `allowSell = false`
- composite has no allowed buy member
- composite has no allowed sell member

Example:
```json
{
  "policy": "CLASSIC_FULL_REBALANCE",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "OLD1", "quantity": 10, "price": 100, "allowTrade": true, "allowBuy": false, "allowSell": false }
    ]
  },
  "composites": [
    {
      "compositeId": "OLD_BUCKET",
      "targetWeight": 0.10,
      "members": ["OLD1"],
      "tradePolicy": {
        "buyMembers": [],
        "sellMembers": [],
        "lockedMembers": ["OLD1"]
      }
    }
  ]
}
```

Expected behavior:
- no sell route exists for the composite
- warnings contain a `NO_SELL_MEMBER` constraint
- status becomes `INFEASIBLE` if no trades are possible, otherwise `PARTIAL`

### 8. Respect sizing, minimum order, and trade caps

The engine applies:
- quantity rounding
- `minQuantity`
- `minNotional`
- `maxNotionalPerOrder`
- `maxNotionalPerAsset`
- `risk.maxSingleTradeNotional`
- `maxOrders`

The same sizing rules apply to leftover-budget top-up orders in buy-only flows, so the engine does not emit dust trades below the configured minimums.

Example:
```json
{
  "policy": "BUY_ONLY",
  "budget": { "amount": 50, "currency": "USD" },
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": []
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 1.0 }
  ],
  "sizing": { "fractionalAllowed": false, "quantityStep": 1, "minNotional": 100, "minQuantity": 1 }
}
```

Expected behavior:
- no feasible order can be created
- status is `INFEASIBLE`
- `bindingConstraints` includes a sizing or budget-related constraint

### 9. Estimate threshold cash gap and exact cash diagnostics for buy-only portfolios

For buy-only policies, the engine runs binary searches with larger budgets and returns:
- `targetThresholdCashGapBefore` / `targetThresholdCashGapAfter` as budget-independent cash needed from the current snapshot to satisfy the configured threshold
- `additionalCashLowerBound`
- `additionalCashForExactTarget` when found within the search range
- `exactTargetCashGapBefore` / `exactTargetCashGapAfter` as stricter budget-independent money distance-to-target diagnostics

Example:
```json
{
  "policy": "BUY_ONLY",
  "portfolio": {
    "cash": { "amount": 0, "currency": "USD" },
    "positions": [
      { "assetId": "VOO", "quantity": 10, "price": 100, "allowTrade": true, "allowBuy": true, "allowSell": true }
    ]
  },
  "targets": [
    { "assetId": "VOO", "targetWeight": 0.50 },
    { "assetId": "BND", "targetWeight": 0.50 }
  ],
  "budget": { "amount": 50, "currency": "USD" },
  "threshold": { "type": "MAX_ABS", "value": 0.01 }
}
```

Expected behavior:
- the result includes `targetThresholdCashGapBefore`
- when the configured threshold is looser than exact matching, `targetThresholdCashGapAfter` can reach zero while `exactTargetCashGapAfter` remains positive
- the result includes `additionalCashLowerBound`
- `additionalCashForExactTarget` may be present if the binary search finds a feasible value
- the result also includes `exactTargetCashGapBefore`
- `targetThresholdCashGapAfter <= targetThresholdCashGapBefore`
- when trades improve the allocation, `exactTargetCashGapAfter <= exactTargetCashGapBefore`

### 10. Normalize target weights when they do not sum to 1 within epsilon

If target weights do not sum to 1 and the mismatch is larger than `options.epsilonWeightSum`, the engine renormalizes them and records a warning.

Example:
```json
{
  "targets": [
    { "assetId": "VOO", "targetWeight": 70 },
    { "assetId": "BND", "targetWeight": 30 }
  ],
  "options": {
    "epsilonWeightSum": 0.000001
  }
}
```

Expected behavior:
- targets are scaled internally
- `debug.inputNormalized` is `true`
- warnings include `Target weights renormalized due to epsilon mismatch`

### 11. Ignore explicitly excluded tickers in rebalance calculations

If `options.ignoredAssetIds` lists a position `assetId`, the engine keeps that ticker visible in request-level reporting but excludes it from rebalance math.

Expected behavior:
- ignored assets do not contribute to total portfolio value used for weights
- ignored assets do not affect deltas, thresholds, or cash-gap calculations
- ignored assets are never selected for trades
- CLI live-price refresh skips ignored assets

### 12. Reject invalid requests before planning

The engine currently validates:
- missing command
- missing portfolio
- negative price
- negative quantity
- the same composite member used in more than one composite

Example:
```json
{
  "portfolio": {
    "positions": [
      { "assetId": "VOO", "quantity": -1, "price": 100 }
    ]
  }
}
```

Expected behavior:
- status is `INVALID_INPUT`
- reason explains the validation failure

## Important gaps between the docs and the actual code

These items appear in the context docs or model enums, but are not implemented as separate behavior in the engine:

- `CONSTRAINED_OPTIMIZATION` does not have a distinct optimizer path. It currently behaves like the generic full-rebalance flow.
- `TAX_AWARE` does not use lots or tax-lot strategy during sell selection.
- `budget.mode` is defined but not used.
- `plan.stepBudgetMode` only supports equal split in practice.
- `plan.spendMode` is defined but not enforced.
- group constraints (`minWeight`, `maxWeight`) are defined but not enforced.
- `minFinalWeight` and `maxFinalWeight` maps are defined but not enforced.
- FX conversion is described in the docs, but current calculations use only `quantity * price`.
- multi-iteration refinement for full rebalance is not implemented.
- explicit exact-target infeasibility explanations are limited to generic binding constraints and warnings.

## Safe summary

If we describe the service strictly from code, the current system is a deterministic rebalancing engine with:
- threshold-based no-op
- buy-only rebalance
- N-step buy-only planning with carry-over
- full rebalance using sell-first then buy
- composite allocation buckets with routed execution members
- fees, sizing, order caps, and basic validation

It is not yet a full implementation of every feature promised in `rebalancing_service_context_v2.md`.
