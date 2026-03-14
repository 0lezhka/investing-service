# Market Data Use Case

This file summarizes the market-data retrieval use case that should be exposed to the rest of the service.

It is intentionally provider-agnostic and defines the application-facing contract, execution rules, and expected behavior for the first version.

## What the use case is for

`FetchMarketDataUseCase` is the single application entry point for retrieving market data needed by service features.

The use case exists so that business flows depend on one stable contract instead of calling provider-specific APIs directly.

This is important for:
- rebalancing
- portfolio valuation
- watchlists
- validation of requested instruments
- future analytics and reporting flows

## What the first version should support

The first version should focus on a basic market snapshot for one or more requested tickers.

Included fields:
- requested ticker
- resolved symbol when available
- instrument name when available
- asset type when available
- exchange when available
- currency when available
- current price
- previous close
- absolute change
- percent change
- open price
- intraday high
- intraday low
- market timestamp
- market state when available
- fetch metadata

Not included yet:
- fundamentals
- ETF holdings
- historical candles
- dividends
- earnings
- analyst recommendations
- technical indicators
- news

These should be added later as extensions of the same market-data contract unless the workflow becomes materially different.

## Main supported scenarios

### 1. Return market data for a single ticker

The use case must support one requested ticker.

Example request shape:
```json
{
  "tickers": ["AAPL"]
}
```

Expected behavior:
- one `MarketDataRecord` may be returned
- unresolved input is reported separately if the symbol cannot be resolved
- provider details are not exposed to the caller

### 2. Return market data for multiple tickers in one call

The use case must support batch retrieval because portfolio and rebalancing flows operate on baskets of instruments.

Example request shape:
```json
{
  "tickers": ["AAPL", "MSFT", "VOO"]
}
```

Expected behavior:
- valid instruments are returned in `instruments`
- unresolved inputs are returned in `unresolvedTickers`
- warnings may be returned when some data is incomplete or unavailable

### 3. Return partial success when some tickers fail

Failure for one ticker must not fail the whole request.

Expected behavior:
- successfully resolved instruments are still returned
- unresolved tickers are listed separately
- non-fatal issues are collected in `warnings`

### 4. Support a default field set with room for future field selection

The first version should return the default basic snapshot set even if callers do not request fields explicitly.

Expected behavior:
- `requestedFields` may be omitted in the first version
- a future caller may request a broader field set without changing the use-case name

### 5. Support strict mode for callers that require at least one result

Some callers may need a stricter failure mode when all requested symbols are missing.

Expected behavior:
- the use case may return an empty result with warnings in normal mode
- `failOnAllMissing = true` throws `AllTickersUnresolvedException` when no instrument is resolved

## Use case contract

### Input

`FetchMarketDataQuery`

| Field | Type | Description |
|---|---|---|
| tickers | `List<String>` | One or more requested symbols |
| requestedFields | `Set<MarketDataField>` | Optional future-proof field selection |
| failOnAllMissing | `boolean` | Optional strict mode for callers that require at least one result |

Input rules:
- `tickers` is required
- blank tickers are discarded during normalization
- duplicate tickers are de-duplicated after normalization
- the first version may ignore omitted `requestedFields` and return the default basic snapshot set
- provider-specific request parameters must not appear in this contract

### Output

`FetchMarketDataResult`

| Field | Type | Description |
|---|---|---|
| instruments | `List<MarketDataRecord>` | Successfully resolved market data records |
| unresolvedTickers | `List<String>` | Requested tickers that could not be resolved |
| warnings | `List<String>` | Non-fatal issues collected during execution |
| fetchedAt | `Instant` | Time when the use case completed |

## Market data record shape

### Identity block

| Field | Description |
|---|---|
| requestedTicker | Symbol received from the caller |
| resolvedSymbol | Canonical symbol returned by the provider |
| name | Human-readable instrument name |
| assetType | Stock, ETF, index, crypto, or unknown |
| exchange | Primary exchange code or name |
| currency | Trading currency |

### Quote block

| Field | Description |
|---|---|
| currentPrice | Latest known price |
| previousClose | Previous session close |
| changeAbsolute | Current price minus previous close |
| changePercent | Relative percentage change |

### Trading block

| Field | Description |
|---|---|
| open | Session open price |
| dayHigh | Session high |
| dayLow | Session low |
| marketTimestamp | Quote timestamp |
| marketState | Open, closed, pre-market, after-hours, or unknown |

### Metadata block

| Field | Description |
|---|---|
| source | Provider identifier |
| stale | Whether data may be outdated |
| partial | Whether some requested fields were unavailable |
| fetchWarnings | Provider-level mapping or completeness warnings |

## Provider boundary

The use case should depend on a provider port such as `MarketDataProvider`.

Responsibility split:

| Component | Responsibility |
|---|---|
| `FetchMarketDataUseCase` | Validates input, orchestrates retrieval, and returns the application result |
| `MarketDataProvider` | Fetches raw market data for requested fields |
| Provider mapper | Maps external payloads to the domain record |
| Cache decorator | Adds caching without changing use-case behavior |
| Fallback or composite provider | Supports provider chaining without changing consumers |

Boundary rules:
- business logic must not know which provider was used
- business logic must not know which external endpoint was called
- provider field names must not leak into the use-case contract
- callers must depend only on the generic market-data model

Incorrect examples of leaked provider detail:
- `c`
- `dp`
- `finnhubSymbol`
- `quoteResponse`

## Error handling and execution rules

The use case should distinguish between:
- unresolved symbol
- missing optional fields
- provider outage
- rate-limit or throttling issue
- malformed provider response

Expected handling:
- unresolved symbols go to `unresolvedTickers`
- missing optional fields may produce a partial record
- non-fatal provider issues go to `warnings`
- a total provider failure may return an empty result with warnings unless strict mode is requested

Execution rules:
- single-ticker and batch requests are both supported
- partial success is the default behavior
- provider exceptions should be isolated so one failure does not stop other tickers
- batch behavior should allow provider-specific batching or concurrency limits behind the abstraction

## Rebalancing fit

For the current service, rebalancing should consume `FetchMarketDataUseCase` and request only the default basic snapshot field set.

The CLI `rebalance run` flow may optionally refresh portfolio position prices before planning when `--load-current-prices` is provided.

CLI refresh rules:
- only portfolio positions are refreshed
- ticker lookup uses `position.symbol`, falling back to `position.assetId` when `symbol` is blank
- refreshed prices replace the input `price` only for the in-memory command used by the CLI run
- the CLI run fails if any requested position price cannot be resolved

Rebalancing should not depend on:
- Finnhub field naming
- provider-specific endpoints
- caching implementation details
- future fallback-provider logic

This keeps rebalancing isolated from provider changes while still allowing the market-data capability to grow.

## Non-functional expectations

### Reliability

- tolerate partial failures
- isolate provider exceptions
- support fallback providers later

### Performance

- support batched retrieval at the use-case layer
- allow provider-specific batching where available
- allow cache decoration later

### Observability

Record at least:
- requested tickers
- resolved tickers
- provider used
- latency
- warnings
- unresolved count

### Security

- API keys must come from configuration
- no secrets in code or documentation examples

## Future extensions

The preferred extension path is to grow `MarketDataField` and `MarketDataRecord` rather than introduce new provider-specific contracts.

Possible future field groups:
- `BASIC_SNAPSHOT`
- `INSTRUMENT_PROFILE`
- `FUNDAMENTALS`
- `ETF_PROFILE`
- `HISTORICAL_PRICES`
- `DIVIDENDS`
- `MARKET_STATUS`

Current implementation note:

- only `BASIC_SNAPSHOT` is implemented

Recommended rule:

New market-data needs should first be modeled as an extension of `FetchMarketDataUseCase`. A separate use case should be introduced only when the workflow becomes materially different, such as long-running historical imports or provider-specific bulk synchronization.

## Safe summary

`FetchMarketDataUseCase` should be the single stable contract for market-data retrieval in the service.

It supports:
- one ticker or many tickers
- partial success
- a default basic snapshot field set
- future extensibility through field groups
- provider isolation behind `MarketDataProvider`

Finnhub may be the first implementation behind this boundary, but it should remain an implementation detail rather than part of the public use-case contract.
