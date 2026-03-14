package com.investing.service.investingservice.marketdata.provider.finnhub;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "market-data.finnhub")
public class FinnhubMarketDataProperties {

    private String baseUrl = "https://finnhub.io/api/v1";
    private String apiKey;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private boolean enableMarketStatus;
    private Cache cache = new Cache();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isEnableMarketStatus() {
        return enableMarketStatus;
    }

    public void setEnableMarketStatus(boolean enableMarketStatus) {
        this.enableMarketStatus = enableMarketStatus;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public static class Cache {
        private boolean enabled = true;
        private Duration quoteTtl = Duration.ofSeconds(30);
        private Duration profileTtl = Duration.ofHours(24);
        private Duration marketStatusTtl = Duration.ofMinutes(5);
        private int batchSize = 25;
        private int maxConcurrency = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getQuoteTtl() {
            return quoteTtl;
        }

        public void setQuoteTtl(Duration quoteTtl) {
            this.quoteTtl = quoteTtl;
        }

        public Duration getProfileTtl() {
            return profileTtl;
        }

        public void setProfileTtl(Duration profileTtl) {
            this.profileTtl = profileTtl;
        }

        public Duration getMarketStatusTtl() {
            return marketStatusTtl;
        }

        public void setMarketStatusTtl(Duration marketStatusTtl) {
            this.marketStatusTtl = marketStatusTtl;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }
}
