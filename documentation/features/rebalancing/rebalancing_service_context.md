# Rebalancing Service — Context for Coding Agent

## Goal
Build a **stateless** service that **generates a rebalance plan** for a portfolio of stocks/ETFs based on:
- **target weights**
- **current weights**
- **recommended trades**
- **buy-only mode**
- **rebalance threshold**
- **fees + rounding rules**
- **buy plan**: generate a **chain of N buy steps** for a given budget to reach target with **minimal delta** while respecting constraints.

The service is **calculation-only** for now:
- It receives **one command** with **all inputs included** (no external price feeds, no brokerage integration).
- It returns a **plan** and **metrics** that describe plan quality and “distance to target”.

## Non-goals (for now)
- Broker integration (placing orders).
- Persisting portfolios or plans (stateless API).
- Real-time quotes / FX fetching (all prices and FX are provided in input).

---

## High-level Features (must implement)
### Allocation & Weights
- Parse target allocation (weights sum to 1.0 ± ε).
- Compute current market value per position and **current weights**.
- Compute deviations (current - target) per asset.

### Plans
1) **Full Rebalance (buy + sell)**  
   Generate trades that bring portfolio as close as possible to target within constraints.

2) **Buy-only Rebalance (single-step)**  
   Given a **budget** (new cash), generate **only BUY** trades that minimize delta to target.

3) **Buy Plan with N steps**
   Given:
   - total budget `B`
   - number of steps `N`
   - step sizing policy (equal steps by default)

   Generate a **sequence** of N buy-only trade batches, applying each step to the simulated portfolio state.
   - Objective: **minimize final delta** to target (and optionally intermediate deltas).
   - Must respect rounding, min order sizes, fees, and other constraints at every step.

### Threshold / No-op
- If portfolio is “close enough”, return **NO_TRADES** with metrics.
- Threshold can be configured by:
  - max absolute deviation in % points (e.g., 0.5% = 0.005)
  - total L1 deviation (sum of abs deviations)
  - value-based deviation in base currency

### Fees & Rounding
- Support:
  - fixed fee per order
  - percentage fee (bps)
  - min fee
- Rounding / order sizing:
  - whole shares only OR fractional shares allowed
  - quantity step (e.g., 0.0001)
  - minimum notional per order
  - minimum quantity per order

### Effectiveness Metrics
For each run, compute:
- **Delta metrics** before and after:
  - `L1`: sum(|w_i - t_i|)
  - `L2`: sqrt(sum((w_i - t_i)^2))
  - `maxAbs`: max(|w_i - t_i|)
  - `trackingErrorLike`: (optional) L2 on deviations
- **Threshold cash gap** before and after for buy-only policies:
  - extra external cash needed from the current snapshot to satisfy the configured threshold
  - computed independently from the configured request budget
  - if no threshold is configured, the current implementation falls back to the exact-target gap
  - `n/a` for policies that can sell
- **Exact target cash gap** before and after for buy-only policies:
  - extra external cash needed from the current snapshot to hit exact targets
  - computed independently from the configured request budget
  - `n/a` for policies that can sell
- **Required extra cash to reach target exactly** (or closest feasible):
  - `additionalCashForExactTarget` (>=0) given constraints and buy-only/full mode
  - If exact target infeasible due to rounding/constraints, return:
    - `additionalCashLowerBound`
    - `explanation` / `blockingConstraints`
- **Turnover / trading cost**
  - total buy notional, total sell notional
  - estimated total fees
- **Feasibility & saturation**
  - which constraints were binding
  - assets capped by max trade / locked positions / etc.

---

## Policies (implement all)
Expose a `policy` enum; implement behavior accordingly.

### 1) CLASSIC_FULL_REBALANCE
- Allows BUY and SELL.
- Objective: minimize delta to target after trades.
- Constraints apply (locked positions, min order, rounding, max trade, etc.).

### 2) BUY_ONLY
- Only BUY trades, using provided budget.
- Objective: minimize delta after spending up to budget (or exactly budget if configured).

### 3) BUY_ONLY_PLAN_N
- Same as BUY_ONLY, but split budget across N steps and generate a chain.

### 4) CONSTRAINED_OPTIMIZATION
- Full or buy-only, but with additional constraints:
  - locked assets (no trade)
  - restricted assets (no buy / no sell)
  - min/max final weights per asset or per group
  - per-asset max notional/quantity per step
  - leverage disabled (cash cannot go negative)

> Note: `CONSTRAINED_OPTIMIZATION` is not separate code; it is the **core engine**.  
> Other policies are special cases of constraints + allowed trade directions.

### 5) TAX_AWARE (simplified but implemented)
- Input can include lots with cost basis.
- When SELL is allowed, prefer selling lots that minimize realized gains (configurable FIFO/LIFO/MinGain).
- Still prioritize allocation objective; tax objective is **secondary** (lexicographic or weighted).

---

## Input Command Schema (single request)

### RebalanceCommand (JSON)
```json
{
  "requestId": "uuid",
  "asOf": "2026-03-03T12:00:00Z",
  "baseCurrency": "USD",
  "policy": "BUY_ONLY_PLAN_N",

  "portfolio": {
    "cash": { "amount": 1200.00, "currency": "USD" },
    "positions": [
      {
        "symbol": "VOO",
        "assetId": "VOO",
        "currency": "USD",
        "quantity": 10.25,
        "price": 480.12,
        "allowTrade": true,
        "allowBuy": true,
        "allowSell": true,
        "lots": [
          { "quantity": 5.0, "costPrice": 410.00, "acquiredAt": "2024-01-10" }
        ]
      }
    ]
  },

  "targets": [
    { "assetId": "VOO", "targetWeight": 0.60 },
    { "assetId": "VXUS", "targetWeight": 0.30 },
    { "assetId": "BND", "targetWeight": 0.10 }
  ],

  "budget": {
    "mode": "NEW_CASH_ONLY",
    "amount": 500.00,
    "currency": "USD"
  },

  "plan": {
    "steps": 5,
    "stepBudgetMode": "EQUAL",
    "spendMode": "UP_TO_BUDGET"
  },

  "threshold": {
    "type": "MAX_ABS",
    "value": 0.005
  },

  "fees": {
    "perOrderFixed": 0.35,
    "percentOfNotional": 0.0005,
    "minFee": 0.35
  },

  "sizing": {
    "fractionalAllowed": true,
    "quantityStep": 0.0001,
    "minNotional": 1.00,
    "minQuantity": 0.0001
  },

  "constraints": {
    "noShort": true,
    "maxOrders": 100,
    "maxNotionalPerOrder": 25000.0,
    "maxNotionalPerAsset": 50000.0,
    "minFinalWeight": {},
    "maxFinalWeight": {},
    "groups": [
      {
        "name": "equity",
        "assetIds": ["VOO", "VXUS"],
        "minWeight": 0.80,
        "maxWeight": 1.00
      }
    ],
    "risk": {
      "maxSingleTradeNotional": 30000.0,
      "killSwitch": false
    }
  },

  "options": {
    "epsilonWeightSum": 0.000001,
    "objective": {
      "primary": "MIN_L2",
      "secondary": ["MIN_FEES", "MIN_TAX"]
    },
    "roundingMode": "FLOOR",
    "includeIntermediateMetrics": true
  }
}
```

### Notes
- All symbols/assetIds must be stable identifiers within the request; mapping is not required.
- If an asset exists in `targets` but not in `positions`, it is treated as 0 quantity (buy candidate).
- If an asset exists in positions but not in targets: allowed but its target weight is 0 (unless `options.allowExtraAssets=true`).
- If `options.ignoredAssetIds` contains an `assetId`, that position remains visible in reporting but is excluded from totals, weights, thresholds, cash-gap calculations, and trade generation.

---

## Output Schema

### RebalanceResult
```json
{
  "requestId": "uuid",
  "policy": "BUY_ONLY_PLAN_N",
  "status": "OK",
  "reason": null,

  "metrics": {
    "before": { "l1": 0.084, "l2": 0.052, "maxAbs": 0.061 },
    "after":  { "l1": 0.012, "l2": 0.008, "maxAbs": 0.010 },
    "targetThresholdCashGapBefore": 24.50,
    "targetThresholdCashGapAfter": 0.0,
    "exactTargetCashGapBefore": 272.10,
    "exactTargetCashGapAfter": 22.10,
    "feesEstimate": 1.75,
    "turnover": { "buyNotional": 500.0, "sellNotional": 0.0 },
    "additionalCashForExactTarget": 22.10,
    "additionalCashLowerBound": 12.00,
    "bindingConstraints": ["MIN_NOTIONAL", "ROUNDING_STEP"]
  },

  "current": {
    "totalValue": 12345.67,
    "weights": { "VOO": 0.58, "VXUS": 0.31, "BND": 0.11 }
  },

  "recommended": {
    "trades": [
      { "step": 1, "assetId": "VOO", "side": "BUY", "quantity": 0.12, "notional": 57.61, "fee": 0.35 },
      { "step": 1, "assetId": "BND", "side": "BUY", "quantity": 0.40, "notional": 35.20, "fee": 0.35 }
    ]
  },

  "steps": [
    {
      "step": 1,
      "budget": 100.0,
      "trades": [...],
      "metricsAfterStep": { "l1": 0.040, "l2": 0.028, "maxAbs": 0.030 }
    }
  ],

  "debug": {
    "warnings": ["Target weights renormalized due to epsilon mismatch"],
    "inputNormalized": false
  }
}
```

---

## Domain Model (recommended)

### Core value objects
- `Money(amount, currency)`
- `AssetId(String)`
- `Position(assetId, symbol, currency, quantity, price, allowTrade, allowBuy, allowSell, lots?)`
- `Lot(quantity, costPrice, acquiredAt)`
- `Target(assetId, targetWeight)`
- `Trade(assetId, side, quantity, price, notional, fee, step?)`
- `TradeBatch(step, budget, trades, metricsAfterStep?)`

### Services
- `WeightsService`
  - compute total value, weights, deviations
- `FeeModel`
  - feeForOrder(notional): fixed + percent with min fee
- `SizingModel`
  - apply rounding / step / min notional / min qty
- `OptimizerEngine`
  - solve trades for a given policy & constraints
- `Planner`
  - for N-step plan: loop N times, update simulated portfolio after each step

---

## Core Calculations

### Current value and weights
- For each position:
  - `value_i = quantity_i * price_i` in position currency
  - convert to base currency with provided FX rates (if any; otherwise assume currency == base)
- `total = cash + Σ value_i`
- `weight_i = value_i / total`

### Delta / distance to target
Let `d_i = weight_i - target_i` (missing target -> 0).
Compute:
- `L1 = Σ |d_i|`
- `L2 = sqrt(Σ d_i^2)`
- `maxAbs = max(|d_i|)`

Threshold checks:
- `MAX_ABS`: `maxAbs <= threshold`
- `L1`: `L1 <= threshold`
- `VALUE`: translate deviations into value and compare with threshold value

---

## Optimization Approach (implementation guidance)

### Objective
Primary objective options:
- `MIN_L2` (smooth, good default)
- `MIN_L1` (robust)
- `MIN_MAX_ABS` (minimax)

Secondary objectives (tie-breakers):
- minimize fees
- minimize tax impact
- minimize number of orders

### Decision variables
For each asset i:
- `x_i` = trade notional in base currency (positive buy, negative sell) for single-step
For N-step:
- `x_{i,s}` per step s

After trades:
- `value'_i = value_i + x_i` (approx, ignoring price impact)
- `cash' = cash + budget - Σ (x_i + fee_i)` for buy-only
- `total' = cash' + Σ value'_i`
- `weight'_i = value'_i / total'`

Because weights are fractional, exact optimization is non-linear.
**Practical implementation**:
1) Solve in **notional space** with iterative linearization:
   - assume `total' ≈ total + budget` (or include estimated fees)
   - target value for asset i: `tv_i = target_i * total'`
   - desired change: `x*_i = tv_i - value_i`
2) Apply constraints & direction limits.
3) Apply rounding/min-notional, recompute totals, iterate a few times (e.g., 3–10) until stable.

This is fast and works well for rebalancing.

### Constraints to implement
- Trade direction:
  - buy-only: `x_i >= 0`
  - no-buy: `x_i = 0` or `x_i <= 0` depending
- Locked: `x_i = 0`
- No short: final quantity must be >= 0
- Max notional per order / per asset / per step
- Max orders
- Group min/max weights (post-trade):
  - sum weights of group assets within [min, max]
- Min order notional & quantity, and rounding step

### Rounding & sizing
After computing ideal `x_i`, translate to quantity:
- `qty_i = x_i / price_i`
- apply `quantityStep` rounding (floor/nearest/ceil as configured)
- ensure `notional >= minNotional` and `qty >= minQuantity`
- if constraint fails, set to 0 and record `bindingConstraints`.

Fees:
- apply per order, recompute leftover cash/budget.
- If fees cause overspend, scale down or drop smallest orders.

---

## N-step Buy Plan Algorithm
Inputs: total budget `B`, steps `N`, step budgets `b_s`.

Default: `b_s = B / N` (last step gets remainder to match exactly).

Algorithm (deterministic):
1) `state = initial portfolio`
2) For each step s in 1..N:
   - compute current weights in state
   - compute desired notional deltas vs target using `total' = state.total + b_s`
   - restrict to BUYs only
   - run sizing & fees
   - if no feasible trades, stop early
   - apply trades to state (update quantities, cash)
   - store batch and metricsAfterStep
3) Return batches + final metrics.

Optimization refinement (must implement):
- If equal-step planning yields poor final delta due to rounding/min order:
  - allow the planner to “carry over” unused budget to next step
  - last step can be larger to spend remainder
  - optional: small local search on last 1–2 steps to reduce delta (swap small orders)

---

## “Additional cash to reach exact target”
Define “exact” as:
- final weights within tolerance `τ` for all assets (`|d_i| <= τ`) OR
- delta metric below threshold

Compute:
1) For policy BUY_ONLY:
   - find minimal extra cash `C` such that achievable delta <= τ.
   - Use binary search on C:
     - simulate buy-only plan with budget `B + C`
     - check if achieved
   - return lower bound and best-found feasible.
2) For CLASSIC_FULL_REBALANCE:
   - usually exact is feasible unless constrained by rounding/locks.
   - if infeasible, same binary search on “relaxation” is not meaningful; return infeasible explanation.

Return:
- `additionalCashLowerBound` always
- `additionalCashForExactTarget` if found within max search cap
- `blockingConstraints` if not found

---

## Error Handling
Return `status` and structured errors:
- `INVALID_INPUT` (weights not summing, negative prices, missing prices)
- `INFEASIBLE` (constraints prevent any trade)
- `NO_TRADES` (already within threshold)
- `PARTIAL` (some trades feasible but cannot spend full budget due to constraints)

---

## Test Plan (must)
### Unit tests
- weight calculation, FX conversion
- fee model (fixed/percent/min)
- rounding & min-notional behavior
- threshold no-op logic
- buy-only with simple 2-asset portfolio

### Property tests / invariants
- no sells in BUY_ONLY policies
- cash never negative (if `noShort` and `noLeverage`)
- quantities respect step/min constraints
- plan does not exceed budget (including fees), unless `spendMode=UP_TO_BUDGET` and leftover is allowed

### Scenario tests
- portfolio missing target assets (buy new)
- locked overweight asset (cannot sell)
- minNotional causes skipping small trades
- N-step plan with carry-over remainder

---

## Acceptance Criteria
- Given a command with valid inputs:
  - service returns weights, deviations, and a plan consistent with policy
  - metrics show improvement (`after.l2 <= before.l2`) unless infeasible
  - buy-only plan never includes SELL
  - threshold returns NO_TRADES
  - N-step plan returns exactly N steps or fewer if infeasible/early-stop, with per-step metrics

---

## Suggested Tech (Java-friendly)
- Java 21+, Maven/Gradle
- Deterministic math:
  - BigDecimal for money, careful rounding
  - double is acceptable for weights if documented
- Optional optimizer libs:
  - simple iterative heuristic first (recommended)
  - if needed later: ojAlgo or OptaPlanner (but keep current implementation library-free if possible)

---

## Implementation Notes
- Keep engine pure and testable:
  - `RebalanceService.rebalance(command) -> result`
  - no IO
- Provide deterministic ordering (sort assets by underweight descending) to ensure stable output.
- Always include `bindingConstraints` and warnings for transparency.
