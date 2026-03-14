package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubMarketStatusResponse(
        @JsonProperty("isOpen") Boolean isOpen
) {
}
