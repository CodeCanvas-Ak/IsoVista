package checker.elle;

import checker.Checker;
import config.Config;
import exceptions.NotImplementedException;
import history.History;
import history.loader.ElleHistoryLoader;
import history.serializer.ElleHistorySerializer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import util.RuntimeInfoRecorder;
import util.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static util.Profiler.updateMemory;

@Slf4j
public class Elle<VarType, ValType> implements Checker<VarType, ValType> {
    public static final String NAME = "ELLE_TCC";
    private static final String ELLE_CLI_PATH = "./third_party/elle-cli-0.1.7-standalone.jar";
    private static final String MODEL = "list-append";
    private static final String CONSISTENCY_MODEL = "causal-cerone";
    private static final String PLOT_DIRECTORY = "./elle_tmp/plots";
    private static volatile Boolean graphvizAvailable;
    private final Properties config;
    private Map<String, Long> stageTime;

    public Elle(Properties config) {
        this.config = config;
    }


    @SneakyThrows
    private boolean runJar(String path) {
        Process process = startProcess(path);

        RuntimeInfoRecorder.addPid(process.pid());

        AtomicBoolean done = new AtomicBoolean(false);
        var t = new Thread(() -> {
            log.info("Start a thread to update java memory usage");
            while (!done.get()) {
                long mem = utils.getProcessMemoryUsage(process.pid());
                updateMemory(mem * 1024);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Java memory monitor thread stopped");
        });
        t.setDaemon(true);
        t.start();

        StringBuilder output = new StringBuilder();
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info(line);
                    output.append(line).append('\n');
                }
            }

            // match stage time
            String regex = "Stage ([\\w-]+) Time: (\\d+) ms";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(output);

            stageTime = new HashMap<>();

            while (matcher.find()) {
                String stageName = matcher.group(1);
                long time = Long.parseLong(matcher.group(2));
                stageTime.put(stageName, time);
            }
            stageTime.forEach((key, value) -> log.info(key + ": " + value + " ms"));

            int exitCode = process.waitFor();
            log.info("Elle-cli jar exited with code: " + exitCode);
            return parseResult(output.toString(), exitCode);
        } finally {
            done.set(true);
            t.interrupt();
            if (process.isAlive()) {
                process.destroy();
            }
            RuntimeInfoRecorder.removePid(process.pid());
        }
    }

    Process startProcess(String path) throws java.io.IOException {
        // java -jar target/elle-cli-0.1.7-standalone.jar --model list-append --consistency-models causal-cerone histories/elle/list-append-gh-30.edn
        List<String> command = new java.util.ArrayList<>(List.of("java", "-jar", ELLE_CLI_PATH, "--model", MODEL,
                "--consistency-models", CONSISTENCY_MODEL, "--cycle-search-timeout", "10000000"));
        if (plottingEnabled()) {
            command.addAll(List.of("--directory", PLOT_DIRECTORY, "--plot-format", "svg"));
        }
        command.add(path);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * elle-cli renders SCC plots through graphviz and aborts the whole verification when the
     * {@code dot} binary is missing, so plotting stays optional.
     */
    private boolean plottingEnabled() {
        String configured = config.getProperty(Config.ELLE_PLOT);
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured.trim());
        }
        return graphvizAvailable();
    }

    private static boolean graphvizAvailable() {
        Boolean cached = graphvizAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            Process process = new ProcessBuilder("dot", "-V").redirectErrorStream(true).start();
            available = process.waitFor() == 0;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            available = false;
        }
        graphvizAvailable = available;
        return available;
    }

    static boolean parseResult(String output, int exitCode) {
        // elle-cli uses exit code 1 for a valid history evaluation that violates the model.
        if (exitCode != 0 && exitCode != 1) {
            throw new IllegalStateException("Elle-cli exited with code " + exitCode);
        }

        Pattern resultPattern = Pattern.compile("true|false");
        Matcher resultMatcher = resultPattern.matcher(output);
        String lastMatch = null;
        while (resultMatcher.find()) {
            lastMatch = resultMatcher.group();
        }
        if (lastMatch == null) {
            throw new IllegalStateException("No true/false result found in Elle-cli output");
        }
        if (exitCode == 1 && !"false".equals(lastMatch)) {
            throw new IllegalStateException("Elle-cli exited with code 1 but returned " + lastMatch);
        }

        log.info("Elle-cli returned: " + lastMatch);
        return Boolean.parseBoolean(lastMatch);
    }

    @Override
    @SneakyThrows
    public boolean verify(History<VarType, ValType> history) {
        var historyType = config.getProperty(Config.HISTORY_TYPE, Config.DEFAULT_HISTORY_TYPE);
        var skipGeneration = Boolean.parseBoolean(config.getProperty(Config.WORKLOAD_SKIP_GENERATION, "false"));
        preparePlotDirectory();
        if ("elle".equalsIgnoreCase(historyType) && skipGeneration) {            return runJar(config.getProperty(Config.HISTORY_PATH));
        }

        Path tempHistory = Files.createTempFile("isovista-", ".edn");
        try {
            new ElleHistorySerializer().serializeHistory((History<Integer, ElleHistoryLoader.ElleValue>) history, tempHistory.toString());
            return runJar(tempHistory.toString());
        } finally {
            Files.deleteIfExists(tempHistory);
        }
    }

    /**
     * elle-cli only renders SCC plots as png or svg, and only through graphviz. Without it the
     * checker has no counterexample artifact at all.
     */
    @Override
    public String counterexampleFormat() {
        return plottingEnabled() ? "svg" : "none";
    }

    @Override
    public boolean supportsHistoryType(String historyType) {
        return "elle".equalsIgnoreCase(historyType);
    }

    @SneakyThrows
    private void preparePlotDirectory() {
        if (!plottingEnabled()) {
            return;
        }
        Path plotDirectory = Path.of(PLOT_DIRECTORY);
        Files.createDirectories(plotDirectory);
        try (var plots = Files.list(plotDirectory)) {
            for (Path plot : plots.collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(plot);
            }
        }
    }

    @Override
    @SneakyThrows
    public void outputCounterexample(String directory) {
        if (!plottingEnabled()) {
            throw new IllegalStateException("Elle plotting is disabled: graphviz is missing or checker.elle_plot is false");
        }
        Path plotDirectory = Path.of(PLOT_DIRECTORY);
        if (!Files.isDirectory(plotDirectory)) {
            throw new IllegalStateException("Elle-cli did not create a plot directory");
        }
        List<Path> plots;
        try (var files = Files.list(plotDirectory)) {
            plots = files.filter(path -> path.toString().endsWith(".svg")).sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
        if (plots.isEmpty()) {
            throw new IllegalStateException("Elle-cli did not render an SCC plot");
        }
        Path targetDirectory = Path.of(directory);
        Files.createDirectories(targetDirectory);
        for (int index = 0; index < plots.size(); index++) {
            Files.copy(plots.get(index), targetDirectory.resolve(String.format("conflict-%d.svg", index)),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void outputDotFile(String path) {
        throw new NotImplementedException();
    }

    @Override
    public Map<String, Long> getProfileInfo() {
        if (stageTime == null) {
            return Map.of();
        }
        Map<String, Long> profile = new HashMap<>();
        profile.put("Construction", stageTime.getOrDefault("construction", 0L));
        profile.put("Traversal", stageTime.getOrDefault("scc", 0L) + stageTime.getOrDefault("cycle-search", 0L));
        return profile;
    }
}
