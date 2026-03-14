package com.investing.service.investingservice.rebalancing.cli;

import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class RebalanceCliParser {

    public RebalanceCliCommand parse(String[] args) {
        if (args == null || args.length == 0) {
            return new RebalanceCliCommand.HelpCommand();
        }

        String command = args[0];
        List<String> flags = tail(args);
        return switch (command) {
            case "help" -> new RebalanceCliCommand.HelpCommand();
            case "run" -> parseRun(flags);
            case "validate" -> parseValidate(flags);
            case "template" -> parseTemplate(flags);
            default -> throw new RebalanceCliUsageException("Unknown command: " + command);
        };
    }

    private RebalanceCliCommand.RunCommand parseRun(List<String> args) {
        String input = null;
        RebalanceCliCommand.OutputFormat output = RebalanceCliCommand.OutputFormat.SUMMARY;
        boolean pretty = false;
        boolean loadCurrentPrices = false;
        BigDecimal budget = null;
        RebalanceCommand.Policy policy = null;
        Integer steps = null;
        RebalanceCommand.ThresholdType thresholdType = null;
        BigDecimal thresholdValue = null;
        boolean failOnPartial = false;
        boolean failOnNoTrades = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--input" -> input = requireValue(args, ++i, arg);
                case "--output" -> output = RebalanceCliCommand.OutputFormat.parse(requireValue(args, ++i, arg));
                case "--pretty" -> pretty = true;
                case "--load-current-prices" -> loadCurrentPrices = true;
                case "--budget" -> budget = new BigDecimal(requireValue(args, ++i, arg));
                case "--policy" -> policy = RebalanceCommand.Policy.valueOf(requireValue(args, ++i, arg));
                case "--steps" -> steps = Integer.valueOf(requireValue(args, ++i, arg));
                case "--threshold-type" -> thresholdType = RebalanceCommand.ThresholdType.valueOf(requireValue(args, ++i, arg));
                case "--threshold-value" -> thresholdValue = new BigDecimal(requireValue(args, ++i, arg));
                case "--fail-on-partial" -> failOnPartial = true;
                case "--fail-on-no-trades" -> failOnNoTrades = true;
                default -> throw new RebalanceCliUsageException("Unknown flag for run: " + arg);
            }
        }

        if (input == null || input.isBlank()) {
            throw new RebalanceCliUsageException("Missing required flag: --input");
        }

        return new RebalanceCliCommand.RunCommand(
                input,
                output,
                pretty,
                loadCurrentPrices,
                budget,
                policy,
                steps,
                thresholdType,
                thresholdValue,
                failOnPartial,
                failOnNoTrades
        );
    }

    private RebalanceCliCommand.ValidateCommand parseValidate(List<String> args) {
        String input = null;
        RebalanceCliCommand.OutputFormat output = RebalanceCliCommand.OutputFormat.SUMMARY;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--input" -> input = requireValue(args, ++i, arg);
                case "--output" -> output = RebalanceCliCommand.OutputFormat.parse(requireValue(args, ++i, arg));
                default -> throw new RebalanceCliUsageException("Unknown flag for validate: " + arg);
            }
        }

        if (input == null || input.isBlank()) {
            throw new RebalanceCliUsageException("Missing required flag: --input");
        }

        return new RebalanceCliCommand.ValidateCommand(input, output);
    }

    private RebalanceCliCommand.TemplateCommand parseTemplate(List<String> args) {
        RebalanceCommand.Policy policy = RebalanceCommand.Policy.BUY_ONLY;
        boolean withComposite = false;
        boolean pretty = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--policy" -> policy = RebalanceCommand.Policy.valueOf(requireValue(args, ++i, arg));
                case "--with-composite" -> withComposite = true;
                case "--pretty" -> pretty = true;
                default -> throw new RebalanceCliUsageException("Unknown flag for template: " + arg);
            }
        }

        return new RebalanceCliCommand.TemplateCommand(policy, withComposite, pretty);
    }

    private String requireValue(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new RebalanceCliUsageException("Missing value for flag: " + flag);
        }
        return args.get(index);
    }

    private List<String> tail(String[] args) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            values.add(args[i]);
        }
        return values;
    }
}
