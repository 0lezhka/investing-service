package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.InvestingCliApplication;
import com.investing.service.investingservice.marketdata.provider.finnhub.FinnhubMarketDataProperties;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestingCliApplicationIT {

    @Test
    void startsCliContextAndExposesDispatcher() {
        try (ConfigurableApplicationContext context = startCliContext()) {
            assertNotNull(context.getBean(RebalanceCliDispatcher.class));
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty());
            assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
            assertTrue(context.getBeansOfType(StringRedisTemplate.class).isEmpty());
        }
    }

    @Test
    void bindsFinnhubApiKeyInCliContext() {
        try (ConfigurableApplicationContext context = startCliContext()) {
            FinnhubMarketDataProperties properties = context.getBean(FinnhubMarketDataProperties.class);
            assertEquals("test-cli-api-key", properties.getApiKey());
        }
    }

    private ConfigurableApplicationContext startCliContext() {
        return new SpringApplicationBuilder(InvestingCliApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--market-data.finnhub.api-key=test-cli-api-key",
                        "--market-data.finnhub.cache.enabled=false",
                        "--spring.data.jpa.repositories.enabled=false",
                        "--spring.data.redis.repositories.enabled=false",
                        "--spring.flyway.enabled=false",
                        "--spring.autoconfigure.exclude[0]=%s".formatted(DataSourceAutoConfiguration.class.getName()),
                        "--spring.autoconfigure.exclude[1]=%s".formatted(HibernateJpaAutoConfiguration.class.getName()),
                        "--spring.autoconfigure.exclude[2]=%s".formatted(FlywayAutoConfiguration.class.getName()),
                        "--spring.autoconfigure.exclude[3]=%s".formatted(DataRedisAutoConfiguration.class.getName())
                );
    }
}
