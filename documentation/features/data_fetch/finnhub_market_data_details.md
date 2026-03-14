# Finnhub Market Data Details

## Purpose

This document describes how Finnhub should be integrated as the first `MarketDataProvider` behind `FetchMarketDataUseCase`.

Finnhub is treated as an implementation detail. The service contract must stay provider-agnostic.

## Finnhub Role in the Design

Recommended component name:

`FinnhubMarketDataProvider`

Responsibilities:

- call Finnhub endpoints required for the requested market-data fields
- combine raw Finnhub responses into one internal market-data record
- map Finnhub-specific payloads to the generic domain model
- translate Finnhub failures into unresolved tickers or warnings

Non-responsibilities:

- business decisions for rebalancing
- provider-specific types leaking into application or domain layers
- direct use by business services outside the market-data use case

## First-Version Data Coverage

For the first version, Finnhub should supply only the basic snapshot-style data needed by `FetchMarketDataUseCase`.

### Required Data

| Domain Need | Finnhub Source |
|---|---|
| current price | quote endpoint |
| previous close | quote endpoint |
| absolute change | quote endpoint |
| percent change | quote endpoint |
| open | quote endpoint |
| intraday high | quote endpoint |
| intraday low | quote endpoint |
| quote timestamp | quote endpoint |
| instrument name | company profile endpoint |
| exchange | company profile endpoint |
| currency | company profile endpoint |
| market state | market status endpoint when needed |

### Optional in First Version

- resolved symbol when Finnhub confirms it
- asset type if it can be derived safely
- stale or partial flags based on response completeness

## Suggested Finnhub Endpoints

### Quote

`GET /quote?symbol={symbol}`

Use for:

- current price
- absolute change
- percent change
- session high
- session low
- open
- previous close
- timestamp

### Company Profile

`GET /stock/profile2?symbol={symbol}`

Use for:

- company or instrument name
- exchange
- currency
- ticker confirmation

### Market Status

`GET /stock/market-status?exchange={exchange}`

Use for:

- market open or closed state

This endpoint should be optional in the first implementation if it adds too much request cost. The field may be returned as `UNKNOWN` when the market status is not fetched.

## Mapping Rules

Finnhub payload fields must be translated into generic internal fields.

### Quote Mapping

| Finnhub Field | Meaning | Domain Field |
|---|---|---|
| `c` | current price | `quote.currentPrice` |
| `pc` | previous close | `quote.previousClose` |
| `d` | absolute change | `quote.changeAbsolute` |
| `dp` | percent change | `quote.changePercent` |
| `o` | open | `trading.open` |
| `h` | high | `trading.dayHigh` |
| `l` | low | `trading.dayLow` |
| `t` | unix timestamp | `trading.marketTimestamp` |

### Profile Mapping

| Finnhub Field | Domain Field |
|---|---|
| `ticker` | `resolvedSymbol` |
| `name` | `name` |
| `exchange` | `exchange` |
| `currency` | `currency` |

## Asset-Type Handling

Finnhub does not guarantee a perfectly aligned generic asset-type field for every instrument we may query.

Recommended first-version behavior:

- map asset type only when we have a reliable source
- otherwise return `UNKNOWN`
- do not infer aggressively from symbol format alone

Current implementation:

- `assetType` is always returned as `UNKNOWN`

This keeps the domain model honest and avoids encoding weak provider heuristics as business truth.

## Request Orchestration

For one ticker, the provider may need multiple Finnhub calls to assemble a complete record.

Recommended sequence:

1. Call quote endpoint.
2. Call profile endpoint.
3. Optionally call market-status endpoint if exchange is known and the caller needs market state.
4. Merge the responses into one `MarketDataRecord`.

Current implementation detail:

- market-status is fetched only when `market-data.finnhub.enable-market-status=true`
- Redis-backed cache is checked before any outbound Finnhub request
- uncached work is processed in bounded batches with bounded concurrency
- market-status lookups are deduplicated per exchange and reused across tickers

For multiple tickers:

- process each ticker independently
- preserve partial success
- allow concurrency limits to avoid rate-limit spikes

## Partial-Success Rules

Finnhub integration should support these cases cleanly.

### Quote Available, Profile Missing

Return a record with:

- quote fields populated
- identity fields partially populated
- `metadata.partial = true`
- warning explaining missing profile data

### Profile Available, Quote Missing

This is less useful for rebalancing. Recommended behavior:

- treat the ticker as unresolved for the basic snapshot request
- add a warning if profile data was found but quote data was unavailable

### Invalid Symbol

Recommended behavior:

- add the requested ticker to `unresolvedTickers`
- do not fail the entire batch

Current implementation detail:

- a quote is treated as unusable when Finnhub returns no timestamp or a zero timestamp

### Rate Limit or Temporary Provider Failure

Recommended behavior:

- mark affected ticker as unresolved or partial depending on what was obtained
- add warning text suitable for logs and diagnostics
- keep other tickers processing

Current implementation detail:

- HTTP 429 is translated to a warning mentioning Finnhub rate limiting
- HTTP 5xx is translated to a warning mentioning temporary provider failure

## Market State Strategy

`marketState` should be optional from the provider perspective.

Recommended first version:

- fetch it only if practical
- otherwise return `UNKNOWN`

Rationale:

- rebalancing primarily needs pricing, not exact session state
- market status may require extra requests that increase rate-limit pressure
- the domain contract should allow known, unknown, or missing values without breaking callers

## Configuration

Suggested configuration structure:

```yaml
market-data:
  provider: finnhub
  finnhub:
    base-url: https://finnhub.io/api/v1
    api-key: ${FINNHUB_API_KEY}
    connect-timeout: 2s
    read-timeout: 5s
    enable-market-status: false
    cache:
      enabled: true
      quote-ttl: 30s
      profile-ttl: 24h
      market-status-ttl: 5m
      batch-size: 25
      max-concurrency: 4
```

## Rate Limiting and Throughput

Finnhub rate limits should shape the provider design from the start.

Recommended safeguards:

- cap concurrent outbound requests
- support retry only for transient failures
- avoid retry storms
- keep provider-level caching behind the market-data boundary

Current implementation detail:

- quote responses are cached for a short TTL
- profile responses are cached for a longer TTL
- market-status responses are cached per exchange
- only successful usable payloads are cached
- provider errors and rate-limit responses are not cached

If batch requests become common, the provider should prioritize:

- predictable throughput
- graceful degradation
- clear warnings over aggressive retry behavior

## Error Translation

Finnhub-specific failures must be translated into generic application outcomes.

| Finnhub Situation | Application Outcome |
|---|---|
| invalid or unknown symbol | unresolved ticker |
| HTTP 429 | warning, possible unresolved ticker |
| HTTP 5xx | warning, possible unresolved ticker |
| empty quote payload | unresolved ticker for basic snapshot |
| incomplete payload | partial record when still usable |

## Observability

The provider should log or emit telemetry for:

- requested ticker
- endpoint used
- HTTP status
- latency
- rate-limit responses
- mapping failures
- unresolved outcomes

Logs should not expose API keys or raw sensitive headers.

## Future Finnhub Extensions

Finnhub can support more than the first snapshot version later:

- historical candles
- company metrics
- ETF-related endpoints
- earnings and calendar data
- market news

These should be added by extending `FetchMarketDataUseCase` field groups or by introducing a separate workflow only when the behavior becomes materially different.

## Recommended Boundary

Keep Finnhub code isolated behind:

- `FetchMarketDataUseCase`
- `MarketDataProvider`
- `FinnhubMarketDataProvider`
- Finnhub response DTOs and mappers

Everything above that boundary should depend only on the generic market-data contract.
