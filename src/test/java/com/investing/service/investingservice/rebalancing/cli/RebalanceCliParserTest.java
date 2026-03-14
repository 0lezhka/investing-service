package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RebalanceCliParserTest {

    private final RebalanceCliParser parser = new RebalanceCliParser();

    @Test
    void parsesRunCommandWithOverrides() {
        RebalanceCliCommand command = parser.parse(new String[]{
                "run",
                "--input", "request.json",
                "--output", "json",
                "--pretty",
                "--load-current-prices",
                "--budget", "500",
                "--policy", "BUY_ONLY",
                "--steps", "3",
                "--threshold-type", "MAX_ABS",
                "--threshold-value", "0.01",
                "--fail-on-partial",
                "--fail-on-no-trades"
        });

        RebalanceCliCommand.RunCommand run = assertInstanceOf(RebalanceCliCommand.RunCommand.class, command);
        assertEquals("request.json", run.input());
        assertEquals(RebalanceCliCommand.OutputFormat.JSON, run.outputFormat());
        assertEquals(true, run.loadCurrentPrices());
        assertEquals(new BigDecimal("500"), run.budgetOverride());
        assertEquals(RebalanceCommand.Policy.BUY_ONLY, run.policyOverride());
        assertEquals(3, run.stepsOverride());
        assertEquals(RebalanceCommand.ThresholdType.MAX_ABS, run.thresholdTypeOverride());
        assertEquals(new BigDecimal("0.01"), run.thresholdValueOverride());
    }

    @Test
    void validateRequiresInput() {
        RebalanceCliUsageException exception = assertThrows(RebalanceCliUsageException.class,
                () -> parser.parse(new String[]{"validate"}));
        assertEquals("Missing required flag: --input", exception.getMessage());
    }

    @Test
    void unknownCommandFails() {
        RebalanceCliUsageException exception = assertThrows(RebalanceCliUsageException.class,
                () -> parser.parse(new String[]{"nope"}));
        assertEquals("Unknown command: nope", exception.getMessage());
    }
}
