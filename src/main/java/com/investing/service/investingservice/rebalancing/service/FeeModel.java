package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class FeeModel {

    public BigDecimal feeForOrder(BigDecimal notional, RebalanceCommand.Fees fees) {
        if (notional == null || fees == null || notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal fixed = fees.perOrderFixed() == null ? BigDecimal.ZERO : fees.perOrderFixed();
        BigDecimal percent = fees.percentOfNotional() == null ? BigDecimal.ZERO : fees.percentOfNotional();
        BigDecimal minFee = fees.minFee() == null ? BigDecimal.ZERO : fees.minFee();

        BigDecimal raw = fixed.add(notional.multiply(percent));
        return raw.max(minFee).setScale(8, RoundingMode.HALF_UP);
    }
}
