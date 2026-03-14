package com.investing.service.investingservice.marketdata.provider.finnhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubProfileResponse(
        String ticker,
        String name,
        String exchange,
        String currency
) {
}
