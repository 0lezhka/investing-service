# Market Data Feature

## Purpose

The market-data feature adds a provider-agnostic application entry point for fetching basic quote snapshots for one or more tickers.

The current implementation is centered on:

- `FetchMarketDataUseCase`
- `MarketDataProvider`
- `FinnhubMarketDataProvider`

## Implemented Behavior

The first version supports:

- one ticker or many tickers in a single request
- normalized input tickers
- default field selection through `MarketDataField.BASIC_SNAPSHOT`
- partial success for batch requests
- unresolved tickers returned separately
- warning collection for non-fatal provider issues
- strict mode through `failOnAllMissing`

Strict mode behavior:

- when `failOnAllMissing = false`, the use case returns an empty result with warnings if nothing resolves
- when `failOnAllMissing = true`, the use case throws `AllTickersUnresolvedException` if no instrument is resolved

## Returned Data

Each `MarketDataRecord` contains:

- identity fields such as requested ticker, resolved symbol, name, exchange, and currency
- quote fields such as current price, previous close, absolute change, and percent change
- trading fields such as open, day high, day low, timestamp, and market state
- metadata with provider source, partial flag, stale flag, and provider warnings

Current first-version limitations:

- `assetType` is returned as `UNKNOWN`
- `requestedFields` currently supports the default snapshot path only
- `marketState` is `UNKNOWN` unless Finnhub market-status fetching is enabled

## Finnhub Integration

Finnhub is the first provider behind the market-data boundary.

The provider uses:

- `GET /quote`
- `GET /stock/profile2`
- optionally `GET /stock/market-status`
- Redis-backed cache-first lookup before outbound Finnhub calls
- bounded concurrent batches for uncached tickers and exchanges

Configuration is loaded from application properties:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

market-data:
  provider: finnhub
  finnhub:
    base-url: ${FINNHUB_BASE_URL:https://finnhub.io/api/v1}
    api-key: ${FINNHUB_API_KEY:}
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

Caching behavior:

- quotes are cached briefly to reduce repeated quote traffic during short request windows
- profiles are cached longer because instrument metadata changes rarely
- market-status responses are cached by exchange and reused across tickers
- only successful usable payloads are cached
- errors and rate-limit responses are not cached

Local development:

- `compose.yaml` starts PostgreSQL and Redis for the default local stack
- Redis defaults to `localhost:6379`

Error translation rules:

- unusable quote data resolves as an unresolved ticker
- missing profile data produces a partial record when the quote is still usable
- HTTP 429 becomes a warning about rate limiting
- HTTP 5xx becomes a warning about temporary provider failure
- missing API key returns unresolved tickers with a warning instead of crashing startup

## Verification

Integration coverage exists for:

- successful quote, profile, and market-status mapping
- repeated fetches reusing cached quote, profile, and market-status data
- mixed cached and uncached ticker requests minimizing outbound Finnhub calls
- shared-exchange market-status reuse across multiple tickers
- partial success when one ticker resolves and another is incomplete or missing
- strict-mode failure when all requested tickers are unresolved
