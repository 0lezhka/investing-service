package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.rebalancing.model.RebalanceResult;

public final class RebalanceCliExitCode {

    public static final int OK = 0;
    public static final int NO_TRADES = 2;
    public static final int PARTIAL = 3;
    public static final int INVALID_INPUT = 4;
    public static final int INFEASIBLE = 5;
    public static final int USAGE_ERROR = 6;

    private RebalanceCliExitCode() {
    }

    public static int forResult(RebalanceResult.Status status) {
        if (status == null) {
            return USAGE_ERROR;
        }
        return switch (status) {
            case OK -> OK;
            case NO_TRADES -> NO_TRADES;
            case PARTIAL -> PARTIAL;
            case INVALID_INPUT -> INVALID_INPUT;
            case INFEASIBLE -> INFEASIBLE;
        };
    }
}
