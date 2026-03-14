package com.investing.service.investingservice.marketdata.provider;

import com.investing.service.investingservice.marketdata.model.MarketDataField;

import java.util.List;
import java.util.Set;

public interface MarketDataProvider {

    ProviderFetchResult fetch(List<String> tickers, Set<MarketDataField> requestedFields);
}
