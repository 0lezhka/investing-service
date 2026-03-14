package com.investing.service.investingservice.rebalancing.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investing.service.investingservice.marketdata.service.FetchMarketDataUseCase;
import com.investing.service.investingservice.rebalancing.service.RebalanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.Arrays;

@Component
public class RebalanceCliDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RebalanceCliDispatcher.class);

    private final RebalanceService rebalanceService;
    private final FetchMarketDataUseCase fetchMarketDataUseCase;
    private final ObjectMapper objectMapper;
    private final RebalanceCliParser parser;
    private final RebalanceCliFormatter formatter;

    public RebalanceCliDispatcher(
            RebalanceService rebalanceService,
            FetchMarketDataUseCase fetchMarketDataUseCase,
            ObjectMapper objectMapper
    ) {
        this.rebalanceService = rebalanceService;
        this.fetchMarketDataUseCase = fetchMarketDataUseCase;
        this.objectMapper = objectMapper;
        this.parser = new RebalanceCliParser();
        this.formatter = new RebalanceCliFormatter();
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        log.info("Preparing CLI runner");
        RebalanceCliRunner runner = new RebalanceCliRunner(
                rebalanceService,
                fetchMarketDataUseCase,
                objectMapper,
                formatter,
                out,
                err
        );
        log.info("Parsing CLI arguments: {}", Arrays.toString(args));
        RebalanceCliCommand command = parser.parse(args);
        log.info("Parsed CLI command type: {}", command.getClass().getSimpleName());
        return runner.run(command);
    }

    public static boolean isCliInvocation(String[] args) {
        return args != null && args.length > 0 && "rebalance".equals(args[0]);
    }

    public static String[] cliArgs(String[] args) {
        if (!isCliInvocation(args)) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
