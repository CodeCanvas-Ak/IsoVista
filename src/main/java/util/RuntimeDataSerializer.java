package util;

import checker.result.CheckerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.Config;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Data
public class RuntimeDataSerializer {
    static RuntimeDataSerializer instance;

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final Path outputPath;
    private final long timestamp;
    private final String runId;


    @SneakyThrows
    public static synchronized RuntimeDataSerializer getInstance(String dirPath) {
        return getInstance(dirPath, null);
    }

    /**
     * @param runId optional run directory name; when given it replaces the default timestamp
     *              based name so a scheduler can decide where the artifact lands.
     */
    @SneakyThrows
    public static synchronized RuntimeDataSerializer getInstance(String dirPath, String runId) {
        if (instance == null) {
            var currentTimestamp = System.currentTimeMillis();
            var subDirPath = resolveRunDirectoryName(runId, currentTimestamp);
            var outputPath = Paths.get(dirPath, subDirPath);

            Files.createDirectories(outputPath);
            log.info("Successfully created dir {}", outputPath);

            instance = new RuntimeDataSerializer(outputPath, currentTimestamp, subDirPath);
        }
        return instance;
    }

    static String resolveRunDirectoryName(String runId, long timestamp) {
        if (runId == null || runId.isBlank()) {
            return String.format("run_%d", timestamp);
        }
        if (!SAFE_RUN_ID.matcher(runId.trim()).matches()) {
            throw new IllegalArgumentException("Unsafe run id: " + runId);
        }
        return runId.trim();
    }

    public RuntimeDataSerializer(Path outputPath, long timestamp) {
        this(outputPath, timestamp, outputPath.getFileName().toString());
    }

    public RuntimeDataSerializer(Path outputPath, long timestamp, String runId) {
        this.outputPath = outputPath;
        this.timestamp = timestamp;
        this.runId = runId;
    }


    public void outputToPath(int historyCount, int bugCount, Properties config, boolean enableProfiler) {
        outputToPath(historyCount, bugCount, config, enableProfiler, Paths.get(Config.DEFAULT_CURRENT_PATH));
    }

    void outputToPath(int historyCount, int bugCount, Properties config, boolean enableProfiler, Path sourceDirectory) {
        outputToPath(historyCount, bugCount, config, enableProfiler, sourceDirectory, null, null);
    }

    @SneakyThrows
    public void outputToPath(int historyCount, int bugCount, Properties config, boolean enableProfiler,
                             Path sourceDirectory, List<CheckerResult> checkerResults, RunRecord runRecord) {
        // move all files in current dir
        List<Path> sourceFiles;
        try (var paths = Files.walk(sourceDirectory)) {
            sourceFiles = paths.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (var sourceFile : sourceFiles) {
            Path targetFile = outputPath.resolve(sourceDirectory.relativize(sourceFile));
            Files.createDirectories(targetFile.getParent());
            Files.move(sourceFile, targetFile);
        }

        // serialize config file without credentials; the runtime config remains unchanged
        Properties archivedConfig = withoutSecrets(config);
        try (var configOutputStream = new FileOutputStream(outputPath.resolve("config.properties").toFile())) {
            archivedConfig.store(configOutputStream, "config properties");
        }

        // serialize metadata
        Map<String, Object> outputMap = new HashMap<>();
        var profiler = Profiler.getInstance();
        outputMap.put("have_bug", bugCount > 0);
        outputMap.put("timestamp", timestamp);
        outputMap.put("history_count", historyCount);
        outputMap.put("bug_count", bugCount);
        outputMap.put("db_type", config.get(Config.DB_TYPE));
        outputMap.put("db_isolation", config.get(Config.DB_ISOLATION));
        outputMap.put("checker_isolation", config.get(Config.CHECKER_ISOLATION));
        outputMap.put("run_id", runId);
        outputMap.put("status", "Finished");
        outputMap.put("code_revision", BuildInfo.revision());
        outputMap.put("history_sha256", runRecord == null ? null : runRecord.getHistorySha256());
        if (enableProfiler)
            outputMap.put("run_total_time", profiler.getTotalTime("run_total_time"));
        if (checkerResults != null && !checkerResults.isEmpty()) {
            outputMap.put("checker_results", checkerResults.stream().map(CheckerResult::toMap).collect(Collectors.toList()));
        }

        var objectMapper = new ObjectMapper();
        var json = objectMapper.writeValueAsString(outputMap);

        log.info("{}", json);
        var metadataPath = Paths.get(outputPath.toString(), "metadata.json");
        Files.writeString(metadataPath, json);

        // checker results in the unified schema
        if (checkerResults != null && !checkerResults.isEmpty()) {
            var yaml = new Yaml();
            try (var writer = Files.newBufferedWriter(outputPath.resolve("results.yaml"))) {
                yaml.dump(checkerResults.stream().map(CheckerResult::toMap).collect(Collectors.toList()), writer);
            }
        }

        // run manifest
        if (runRecord != null) {
            if (runRecord.getRunId() == null || runRecord.getRunId().isBlank()) {
                runRecord.setRunId(runId);
            }
            runRecord.setArtifacts(listArtifacts(outputPath));
            if (runRecord.getEndedAt() == null) {
                runRecord.setEndedAt(Instant.now().toString());
            }
            Files.writeString(outputPath.resolve("run.json"), objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(runRecord));
        }
    }

    private static List<String> listArtifacts(Path outputPath) throws IOException {
        List<String> artifacts = new ArrayList<>();
        try (var paths = Files.walk(outputPath)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> outputPath.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(artifacts::add);
        }
        return artifacts;
    }

    private static final Pattern URL_CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)([?;&](?:password|pwd|pass|token|access_token|secret)=)([^&#;]*)");
    private static final Pattern URL_USER_INFO_PASSWORD = Pattern.compile("(?i)(//[^/\\s:@]+:)[^@/\\s]+@");
    private static final String REDACTED = "<redacted>";

    static Properties withoutSecrets(Properties config) {
        Properties archivedConfig = new Properties();
        archivedConfig.putAll(config);
        archivedConfig.remove(Config.DB_PASSWORD);
        String databaseUrl = archivedConfig.getProperty(Config.DB_URL);
        if (databaseUrl != null) {
            archivedConfig.setProperty(Config.DB_URL, sanitizeDatabaseUrl(databaseUrl));
        }
        return archivedConfig;
    }

    private static String sanitizeDatabaseUrl(String databaseUrl) {
        String sanitized = URL_CREDENTIAL_PARAMETER.matcher(databaseUrl).replaceAll("$1" + REDACTED);
        return URL_USER_INFO_PASSWORD.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
    }
}
