package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;

import java.math.BigDecimal;

public sealed interface RebalanceCliCommand permits RebalanceCliCommand.HelpCommand,
        RebalanceCliCommand.RunCommand,
        RebalanceCliCommand.ValidateCommand,
        RebalanceCliCommand.TemplateCommand {

    record HelpCommand() implements RebalanceCliCommand {
    }

    record RunCommand(
            String input,
            OutputFormat outputFormat,
            boolean pretty,
            boolean loadCurrentPrices,
            BigDecimal budgetOverride,
            RebalanceCommand.Policy policyOverride,
            Integer stepsOverride,
            RebalanceCommand.ThresholdType thresholdTypeOverride,
            BigDecimal thresholdValueOverride,
            boolean failOnPartial,
            boolean failOnNoTrades
    ) implements RebalanceCliCommand {
    }

    record ValidateCommand(
            String input,
            OutputFormat outputFormat
    ) implements RebalanceCliCommand {
    }

    record TemplateCommand(
            RebalanceCommand.Policy policy,
            boolean withComposite,
            boolean pretty
    ) implements RebalanceCliCommand {
    }

    enum OutputFormat {
        SUMMARY,
        JSON;

        static OutputFormat parse(String raw) {
            if (raw == null) {
                return SUMMARY;
            }
            return switch (raw.toLowerCase()) {
                case "summary" -> SUMMARY;
                case "json" -> JSON;
                default -> throw new RebalanceCliUsageException("Unsupported output format: " + raw);
            };
        }
    }
}
