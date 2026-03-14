# Rebalance CLI Samples

## IBKR buy-only rebalance with live prices and international composite

This sample:
- uses `BUY_ONLY`
- refreshes current prices with `--load-current-prices`
- groups `VEA`, `VWO`, and `VXUS` into one `INTL` composite with a `16%` target
- routes new international buys through `VXUS`
- ignores `IBKR` in rebalance calculations while keeping it visible in the summary
- limits buys to whole shares
- uses a new cash budget of `250 USD`

How to run:

```powershell
$env:FINNHUB_API_KEY = "YOUR_FINNHUB_API_KEY"
.\scripts\rebalance_ibkr_250_composite.ps1
```

The default `summary` output is now a terminal report intended for humans. It keeps JSON unchanged for automation, but `rebalance run` and `rebalance validate` now print structured sections, ASCII tables, and status banners in summary mode.

Example summary shape:

```text
================================
REBALANCE SUMMARY | Status: OK
================================
[Execution Context]
Request ID         : 2c3c4c1b-...
Policy             : BUY_ONLY
Base Currency      : USD
Positions          : 5
Targets            : 4
Composites         : 1
Current Value      : 8421

[Portfolio And Plan]
Cash               : 0 USD
Budget             : NEW_CASH_ONLY 250 USD
Threshold          : MAX_ABS 0.01
Plan               : not configured

[Metrics Before Vs After]
+----------+------------+------------+------------+
| Metric   | Before     | After      | Delta      |
+----------+------------+------------+------------+
| L1       | 0.21428571 | 0.18234129 | -0.03194442|
| L2       | 0.13192718 | 0.10931354 | -0.02261364|
| Max Abs  | 0.12190476 | 0.10452261 | -0.01738215|
| Tracking | n/a        | n/a        | n/a        |
| Cash Gap | 612.3046875| 362.3046875| -250.0     |
+----------+------------+------------+------------+

Additional Cash Exact: exact=562.3046875, lowerBound=562.0

[Per-Ticker Before Vs After]
+--------+--------+----------+--------------+-------------+-------------+----------+---------+---------+
| Ticker | Target | Target % | Value Before | Value After | Value Delta | % Before | % After | % Delta |
+--------+--------+----------+--------------+-------------+-------------+----------+---------+---------+
| VOO    | VOO    | 60%      | 2838         | 2838        | 0           | 33.70%   | 32.89%  | -0.81%  |
| VXUS   | INTL   | 16%      | 174          | 312         | 138         | 2.07%    | 3.62%   | 1.55%   |
| BND    | BND    | 24%      | 921          | 921         | 0           | 10.94%   | 10.68%  | -0.26%  |
+--------+--------+----------+--------------+-------------+-------------+----------+---------+---------+

[Recommended Trades]
+------+-----+-------+----------+----------+-----+-----------+
| Step | Side| Asset | Quantity | Notional | Fee | Composite |
+------+-----+-------+----------+----------+-----+-----------+
| 1    | BUY | VXUS  | 2        | 138      | 1   | INTL      |
+------+-----+-------+----------+----------+-----+-----------+
```

Script file:

- [scripts/rebalance_ibkr_250_composite.ps1](/C:/Users/oleg1/IdeaProjects/investingService/investingService/scripts/rebalance_ibkr_250_composite.ps1)

Note:
- the current engine can enforce whole-share buys
- it cannot force each order notional to be an exact whole-dollar amount
- `Cash Gap` is the practical extra external cash needed from the current snapshot for buy-only policies to satisfy the configured threshold; sell-capable policies show `n/a`
- `Exact Cash Gap` remains available in structured results as a stricter near-perfect-target diagnostic, but is not shown in the CLI summary
- `Additional Cash Exact` shows extra cash needed on top of the configured budget to reach that stricter exact-target condition, with the current lower bound when the exact value is not found inside the search range
- `Per-Ticker Before Vs After` is a ticker-level summary table built from request positions plus executed trades; the `Target` and `Target %` columns show the direct target asset or composite bucket for each ticker, and composite rows are not shown separately
- ignored positions remain visible in `Per-Ticker Before Vs After` and are labeled `ignored`; they do not contribute to allocation percentages or rebalance math
- the CLI Spring context must include `marketdata.config` so live-price runs can create the shared `Clock` bean used by `FetchMarketDataUseCase`
