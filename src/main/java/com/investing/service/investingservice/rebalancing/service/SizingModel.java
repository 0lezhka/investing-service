package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class SizingModel {

    public BigDecimal roundQuantity(BigDecimal rawQuantity, RebalanceCommand.Sizing sizing, RebalanceCommand.RoundingMode roundingMode) {
        if (rawQuantity == null || sizing == null || rawQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        if (Boolean.FALSE.equals(sizing.fractionalAllowed())) {
            return rawQuantity.setScale(0, map(roundingMode));
        }

        BigDecimal step = sizing.quantityStep() == null || sizing.quantityStep().signum() <= 0
                ? BigDecimal.ONE
                : sizing.quantityStep();
        BigDecimal steps = rawQuantity.divide(step, 0, map(roundingMode));
        return steps.multiply(step).setScale(step.scale(), RoundingMode.HALF_UP);
    }

    public boolean isFeasible(BigDecimal quantity, BigDecimal price, RebalanceCommand.Sizing sizing) {
        if (quantity == null || price == null || sizing == null || quantity.signum() <= 0 || price.signum() <= 0) {
            return false;
        }

        BigDecimal minQty = sizing.minQuantity() == null ? BigDecimal.ZERO : sizing.minQuantity();
        BigDecimal minNotional = sizing.minNotional() == null ? BigDecimal.ZERO : sizing.minNotional();
        BigDecimal notional = quantity.multiply(price);
        return quantity.compareTo(minQty) >= 0 && notional.compareTo(minNotional) >= 0;
    }

    private RoundingMode map(RebalanceCommand.RoundingMode roundingMode) {
        if (roundingMode == null) {
            return RoundingMode.FLOOR;
        }
        return switch (roundingMode) {
            case FLOOR -> RoundingMode.FLOOR;
            case NEAREST -> RoundingMode.HALF_UP;
            case CEIL -> RoundingMode.CEILING;
        };
    }
}
