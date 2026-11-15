package com.vulnscan.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** Root command; subcommands are registered here. */
@Component
@Command(name = "vulnscan", mixinStandardHelpOptions = true,
        subcommands = {RunCommand.class, ScanCommand.class, EvaluateCommand.class, CompareCommand.class, TokensCommand.class,
                GtFromReadmeCommand.class}, version = "vulnscan 0.1.0",
        description = "Static LLM vulnerability scanner for Android apps with a teacher-to-student lesson loop.")
public class VulnscanCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use a subcommand. See --help.");
    }
}
