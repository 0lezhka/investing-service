package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubQuoteResponse(
        @JsonProperty("c") BigDecimal currentPrice,
        @JsonProperty("pc") BigDecimal previousClose,
        @JsonProperty("d") BigDecimal changeAbsolute,
        @JsonProperty("dp") BigDecimal changePercent,
        @JsonProperty("o") BigDecimal open,
        @JsonProperty("h") BigDecimal dayHigh,
        @JsonProperty("l") BigDecimal dayLow,
        @JsonProperty("t") Long timestamp
) {
}
