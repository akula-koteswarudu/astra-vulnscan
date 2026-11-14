package com.vulnscan;

import com.vulnscan.cli.VulnscanCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Spring Boot entry point (non-web). Spring builds the CLI command beans; picocli parses arguments.
 * The exit code of the command becomes the process exit code.
 */
@SpringBootApplication
public class VulnscanApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory factory;
    private final VulnscanCommand rootCommand;
    private int exitCode;

    public VulnscanApplication(IFactory factory, VulnscanCommand rootCommand) {
        this.factory = factory;
        this.rootCommand = rootCommand;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(VulnscanApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(rootCommand, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
