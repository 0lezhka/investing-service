package com.investing.service.investingservice.rebalancing.service;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import org.springframework.stereotype.Service;

@Service
public class RebalanceService {

    private final RebalanceEngine rebalanceEngine;

    public RebalanceService(RebalanceEngine rebalanceEngine) {
        this.rebalanceEngine = rebalanceEngine;
    }

    public RebalanceResult rebalance(RebalanceCommand command) {
        return rebalanceEngine.rebalance(command);
    }
}
