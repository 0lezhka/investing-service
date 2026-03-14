package com.investing.service.investingservice.rebalancing.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataQuery;
import com.investing.service.investingservice.marketdata.model.FetchMarketDataResult;
import com.investing.service.investingservice.marketdata.model.MarketDataRecord;
import com.investing.service.investingservice.marketdata.service.FetchMarketDataUseCase;
import com.investing.service.investingservice.rebalancing.model.RebalanceCommand;
import com.investing.service.investingservice.rebalancing.model.RebalanceResult;
import com.investing.service.investingservice.rebalancing.service.RebalanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RebalanceCliRunner {

    private static final Logger log = LoggerFactory.getLogger(RebalanceCliRunner.class);

    private final RebalanceService rebalanceService;
    private final FetchMarketDataUseCase fetchMarketDataUseCase;
    private final ObjectMapper objectMapper;
    private final RebalanceCliFormatter formatter;
    private final PrintStream out;
    private final PrintStream err;

    public RebalanceCliRunner(
            RebalanceService rebalanceService,
            FetchMarketDataUseCase fetchMarketDataUseCase,
            ObjectMapper objectMapper,
            RebalanceCliFormatter formatter,
            PrintStream out,
            PrintStream err
    ) {
        this.rebalanceService = rebalanceService;
        this.fetchMarketDataUseCase = fetchMarketDataUseCase;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
        this.out = out;
        this.err = err;
    }

    public int run(RebalanceCliCommand command) {
        try {
            log.info("Executing CLI command type: {}", command.getClass().getSimpleName());
            if (command instanceof RebalanceCliCommand.HelpCommand) {
                return help();
            }
            if (command instanceof RebalanceCliCommand.RunCommand runCommand) {
                return runCommand(runCommand);
            }
            if (command instanceof RebalanceCliCommand.ValidateCommand validateCommand) {
                return validateCommand(validateCommand);
            }
            if (command instanceof RebalanceCliCommand.TemplateCommand templateCommand) {
                return templateCommand(templateCommand);
            }
            throw new RebalanceCliUsageException("Unsupported command");
        } catch (IOException exception) {
            log.error("CLI command failed with I/O error", exception);
            err.println("CLI error: " + exception.getMessage());
            return RebalanceCliExitCode.USAGE_ERROR;
        } catch (RebalanceCliExecutionException exception) {
            log.error("CLI command failed during execution", exception);
            err.println("CLI error: " + exception.getMessage());
            return RebalanceCliExitCode.USAGE_ERROR;
        } catch (RebalanceCliUsageException exception) {
            log.warn("CLI command usage error: {}", exception.getMessage());
            err.println("CLI error: " + exception.getMessage());
            err.println();
            err.println(formatter.usage());
            return RebalanceCliExitCode.USAGE_ERROR;
        }
    }

    private int help() {
        log.info("Printing CLI help");
        out.println(formatter.usage());
        return RebalanceCliExitCode.OK;
    }

    private int runCommand(RebalanceCliCommand.RunCommand command) throws IOException {
        log.info("Reading rebalance request from {}", command.input());
        RebalanceCommand request = readRequest(command.input());
        log.info("Loaded rebalance request with {} position(s)", positionCount(request));
        if (command.loadCurrentPrices()) {
            log.info("Refreshing current prices before rebalancing");
            request = loadCurrentPrices(request);
        }
        log.info("Applying CLI overrides");
        request = applyOverrides(request, command);
        log.info("Starting rebalance execution");
        RebalanceResult result = rebalanceService.rebalance(request);
        log.info("Rebalance execution completed with status {}", result.status());
        printResult(request, result, command.outputFormat(), command.pretty());
        return mapExitCode(result.status(), command.failOnPartial(), command.failOnNoTrades());
    }

    private int validateCommand(RebalanceCliCommand.ValidateCommand command) throws IOException {
        log.info("Validating rebalance request from {}", command.input());
        RebalanceCommand request = readRequest(command.input());
        RebalanceResult result = rebalanceService.rebalance(request);
        log.info("Validation completed with status {}", result.status());

        if (command.outputFormat() == RebalanceCliCommand.OutputFormat.JSON) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("valid", result.status() != RebalanceResult.Status.INVALID_INPUT);
            node.put("status", result.status().name());
            if (result.reason() == null) {
                node.putNull("reason");
            } else {
                node.put("reason", result.reason());
            }
            out.println(writeJson(node, true));
        } else {
            out.print(formatter.validationSummary(request, result, formatter.ansiEnabled(isInteractiveOutput())));
        }

        return result.status() == RebalanceResult.Status.INVALID_INPUT
                ? RebalanceCliExitCode.INVALID_INPUT
                : RebalanceCliExitCode.OK;
    }

    private int templateCommand(RebalanceCliCommand.TemplateCommand command) throws JsonProcessingException {
        log.info("Rendering CLI template for policy {} withComposite={}", command.policy(), command.withComposite());
        RebalanceCommand template = formatter.template(command.policy(), command.withComposite());
        out.println(writeJson(template, command.pretty()));
        return RebalanceCliExitCode.OK;
    }

    private RebalanceCommand readRequest(String input) throws IOException {
        String payload;
        if ("-".equals(input)) {
            log.info("Reading CLI request payload from stdin");
            payload = new String(readAll(System.in), StandardCharsets.UTF_8);
        } else {
            log.info("Reading CLI request payload from file {}", input);
            payload = Files.readString(Path.of(input));
        }
        log.debug("CLI request payload size: {} characters", payload.length());
        return objectMapper.readValue(payload, RebalanceCommand.class);
    }

    private int mapExitCode(RebalanceResult.Status status, boolean failOnPartial, boolean failOnNoTrades) {
        if (status == RebalanceResult.Status.PARTIAL && !failOnPartial) {
            return RebalanceCliExitCode.PARTIAL;
        }
        if (status == RebalanceResult.Status.NO_TRADES && !failOnNoTrades) {
            return RebalanceCliExitCode.NO_TRADES;
        }
        return RebalanceCliExitCode.forResult(status);
    }

    private void printResult(
            RebalanceCommand request,
            RebalanceResult result,
            RebalanceCliCommand.OutputFormat outputFormat,
            boolean pretty
    ) throws JsonProcessingException {
        log.info("Printing result as {} (pretty={})", outputFormat, pretty);
        if (outputFormat == RebalanceCliCommand.OutputFormat.JSON) {
            out.println(writeJson(result, pretty));
            return;
        }
        out.print(formatter.summary(request, result, formatter.ansiEnabled(isInteractiveOutput())));
    }

    private boolean isInteractiveOutput() {
        return System.console() != null;
    }

    private String writeJson(Object value, boolean pretty) throws JsonProcessingException {
        ObjectMapper mapper = pretty ? objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT) : objectMapper;
        return mapper.writeValueAsString(value);
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream.readAllBytes();
    }

    private RebalanceCommand loadCurrentPrices(RebalanceCommand command) {
        if (command == null || command.portfolio() == null || command.portfolio().positions() == null || command.portfolio().positions().isEmpty()) {
            log.info("Skipping current price refresh because the portfolio has no positions");
            return command;
        }

        Set<String> ignoredAssetIds = ignoredAssetIds(command);
        Map<String, String> lookupByRequestedTicker = new LinkedHashMap<>();
        for (RebalanceCommand.Position position : command.portfolio().positions()) {
            if (position == null) {
                continue;
            }
            if (ignoredAssetIds.contains(position.assetId())) {
                continue;
            }
            String lookupTicker = lookupTicker(position);
            if (lookupTicker != null) {
                lookupByRequestedTicker.putIfAbsent(lookupTicker, lookupTicker);
            }
        }

        if (lookupByRequestedTicker.isEmpty()) {
            log.info("Skipping current price refresh because no lookup tickers were found");
            return command;
        }

        log.info("Fetching current prices for {} ticker(s): {}", lookupByRequestedTicker.size(), lookupByRequestedTicker.keySet());
        FetchMarketDataResult marketDataResult = fetchMarketDataUseCase.fetch(
                new FetchMarketDataQuery(List.copyOf(lookupByRequestedTicker.keySet()), null, false)
        );
        log.info(
                "Current price fetch completed with {} resolved ticker(s), {} unresolved ticker(s), {} warning(s)",
                marketDataResult.instruments().size(),
                marketDataResult.unresolvedTickers().size(),
                marketDataResult.warnings().size()
        );

        Map<String, BigDecimal> currentPricesByTicker = new LinkedHashMap<>();
        for (MarketDataRecord record : marketDataResult.instruments()) {
            if (record == null || record.currentPrice() == null) {
                continue;
            }
            if (hasText(record.requestedTicker())) {
                currentPricesByTicker.putIfAbsent(record.requestedTicker().trim().toUpperCase(), record.currentPrice());
            }
            if (hasText(record.resolvedSymbol())) {
                currentPricesByTicker.putIfAbsent(record.resolvedSymbol().trim().toUpperCase(), record.currentPrice());
            }
        }

        Set<String> unresolvedTickers = new LinkedHashSet<>();
        List<RebalanceCommand.Position> refreshedPositions = command.portfolio().positions().stream()
                .map(position -> refreshPositionPrice(position, currentPricesByTicker, unresolvedTickers, ignoredAssetIds))
                .toList();

        if (!unresolvedTickers.isEmpty()) {
            log.warn("Current price refresh failed for ticker(s): {}", unresolvedTickers);
            throw new RebalanceCliExecutionException(
                    "Unable to load current prices for: " + String.join(", ", unresolvedTickers)
            );
        }

        log.info("Updated {} portfolio position(s) with current prices", refreshedPositions.size());
        return new RebalanceCommand(
                command.requestId(),
                command.asOf(),
                command.baseCurrency(),
                command.policy(),
                new RebalanceCommand.Portfolio(command.portfolio().cash(), refreshedPositions),
                command.targets(),
                command.composites(),
                command.budget(),
                command.plan(),
                command.threshold(),
                command.fees(),
                command.sizing(),
                command.constraints(),
                command.options()
        );
    }

    private RebalanceCommand.Position refreshPositionPrice(
            RebalanceCommand.Position position,
            Map<String, BigDecimal> currentPricesByTicker,
            Set<String> unresolvedTickers,
            Set<String> ignoredAssetIds
    ) {
        if (position == null) {
            return null;
        }
        if (ignoredAssetIds.contains(position.assetId())) {
            return position;
        }

        String lookupTicker = lookupTicker(position);
        if (lookupTicker == null) {
            unresolvedTickers.add("<missing-symbol>");
            return position;
        }

        BigDecimal currentPrice = currentPricesByTicker.get(lookupTicker);
        if (currentPrice == null) {
            unresolvedTickers.add(lookupTicker);
            return position;
        }

        return new RebalanceCommand.Position(
                position.symbol(),
                position.assetId(),
                position.currency(),
                position.quantity(),
                currentPrice,
                position.allowTrade(),
                position.allowBuy(),
                position.allowSell(),
                position.lots()
        );
    }

    private String lookupTicker(RebalanceCommand.Position position) {
        String ticker = hasText(position.symbol()) ? position.symbol() : position.assetId();
        if (!hasText(ticker)) {
            return null;
        }
        return ticker.trim().toUpperCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Set<String> ignoredAssetIds(RebalanceCommand command) {
        if (command == null || command.options() == null || command.options().ignoredAssetIds() == null) {
            return Set.of();
        }
        return command.options().ignoredAssetIds().stream()
                .filter(this::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private int positionCount(RebalanceCommand command) {
        if (command == null || command.portfolio() == null || command.portfolio().positions() == null) {
            return 0;
        }
        return command.portfolio().positions().size();
    }

    private RebalanceCommand applyOverrides(RebalanceCommand command, RebalanceCliCommand.RunCommand overrides) {
        RebalanceCommand.Policy policy = overrides.policyOverride() != null ? overrides.policyOverride() : command.policy();
        RebalanceCommand.Budget budget = command.budget();
        if (overrides.budgetOverride() != null) {
            budget = new RebalanceCommand.Budget(
                    budget == null ? RebalanceCommand.BudgetMode.NEW_CASH_ONLY : budget.mode(),
                    overrides.budgetOverride(),
                    budget == null ? command.baseCurrency() : budget.currency()
            );
        }

        RebalanceCommand.Plan plan = command.plan();
        if (overrides.stepsOverride() != null) {
            plan = new RebalanceCommand.Plan(
                    overrides.stepsOverride(),
                    plan == null || plan.stepBudgetMode() == null ? RebalanceCommand.StepBudgetMode.EQUAL : plan.stepBudgetMode(),
                    plan == null || plan.spendMode() == null ? RebalanceCommand.SpendMode.UP_TO_BUDGET : plan.spendMode()
            );
        }

        RebalanceCommand.Threshold threshold = command.threshold();
        if (overrides.thresholdTypeOverride() != null || overrides.thresholdValueOverride() != null) {
            threshold = new RebalanceCommand.Threshold(
                    overrides.thresholdTypeOverride() != null
                            ? overrides.thresholdTypeOverride()
                            : threshold == null ? null : threshold.type(),
                    overrides.thresholdValueOverride() != null
                            ? overrides.thresholdValueOverride()
                            : threshold == null ? null : threshold.value()
            );
        }

        log.info(
                "Applied CLI overrides: policyOverride={}, budgetOverride={}, stepsOverride={}, thresholdTypeOverride={}, thresholdValueOverride={}",
                overrides.policyOverride(),
                overrides.budgetOverride(),
                overrides.stepsOverride(),
                overrides.thresholdTypeOverride(),
                overrides.thresholdValueOverride()
        );

        return new RebalanceCommand(
                command.requestId(),
                command.asOf(),
                command.baseCurrency(),
                policy,
                command.portfolio(),
                command.targets(),
                command.composites(),
                budget,
                plan,
                threshold,
                command.fees(),
                command.sizing(),
                command.constraints(),
                command.options()
        );
    }
}
