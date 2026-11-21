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
        // java -jar target/elle-cli-0.1.7-standalone.jar --model list-append --consistency-models snapshot-isolation histories/elle/list-append-gh-30.edn
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", ELLE_CLI_PATH, "--model", MODEL, "--consistency-models", CONSISTENCY_MODEL, "--cycle-search-timeout", "10000000", path);
        pb.redirectErrorStream(true);
        return pb.start();
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
        if ("elle".equalsIgnoreCase(historyType) && skipGeneration) {
            return runJar(config.getProperty(Config.HISTORY_PATH));
        }

        Path tempHistory = Files.createTempFile("isovista-", ".edn");
        try {
            new ElleHistorySerializer().serializeHistory((History<Integer, ElleHistoryLoader.ElleValue>) history, tempHistory.toString());
            return runJar(tempHistory.toString());
        } finally {
            Files.deleteIfExists(tempHistory);
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
