package com.vulnscan.cli;

import com.vulnscan.common.Files2;
import com.vulnscan.common.Json;
import com.vulnscan.evaluate.GroundTruth;
import com.vulnscan.evaluate.ReadmeConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** vulnscan gt-from-readme README.md --out ground_truth/app.yaml */
@Component
@Command(name = "gt-from-readme", description = "Convert a README 'Vulnerabilities' list (N. **Title:** description) into a ground-truth YAML.")
public class GtFromReadmeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "README.md containing the Vulnerabilities section")
    Path readme;

    @Option(names = "--out", required = true, description = "output YAML path")
    Path out;

    @Option(names = "--source", defaultValue = "README \"Vulnerabilities\" section, copied verbatim",
            description = "text recorded in the 'source' field")
    String source;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(out)) {
            System.err.println("refusing to overwrite " + out + " (it may contain hand-written enrichment)");
            return 2;
        }
        GroundTruth gt = new ReadmeConverter().convert(Files.readString(readme), source);
        Files2.writeAtomic(out, Json.yaml().writeValueAsString(gt));
        System.out.println("wrote " + gt.items().size() + " items to " + out);
        return 0;
    }
}
