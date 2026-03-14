package com.investing.service.investingservice;

import com.investing.service.investingservice.rebalancing.cli.RebalanceCliDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class InvestingServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(InvestingServiceApplication.class);

    public static void main(String[] args) {
        if (RebalanceCliDispatcher.isCliInvocation(args)) {
            log.info("Detected CLI invocation with {} argument(s)", args == null ? 0 : args.length);
            int exitCode = runCli(args);
            log.info("CLI invocation finished with exit code {}", exitCode);
            System.exit(exitCode);
        }

        log.info("Starting web application mode");
        SpringApplication.run(InvestingServiceApplication.class, args);
    }

    static int runCli(String[] args) {
        String[] cliArgs = RebalanceCliDispatcher.cliArgs(args);
        log.info("Starting CLI Spring context");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(InvestingCliApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            log.info("CLI Spring context started successfully");
            RebalanceCliDispatcher dispatcher = context.getBean(RebalanceCliDispatcher.class);
            log.info("Dispatching CLI command with {} argument(s)", cliArgs.length);
            return dispatcher.run(cliArgs, System.out, System.err);
        }
    }
}
