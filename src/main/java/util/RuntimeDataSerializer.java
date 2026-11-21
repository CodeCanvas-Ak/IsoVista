package util;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.Config;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

@Slf4j
@Data
public class RuntimeDataSerializer {
    static RuntimeDataSerializer instance;

    private final Path outputPath;
    private final long timestamp;


    @SneakyThrows
    public static synchronized RuntimeDataSerializer getInstance(String dirPath) {
        if (instance == null) {
            var currentTimestamp = System.currentTimeMillis();
            var subDirPath = String.format("run_%d", currentTimestamp);
            var outputPath = Paths.get(dirPath, subDirPath);

            Files.createDirectories(outputPath);
            log.info("Successfully created dir {}", outputPath);

            instance = new RuntimeDataSerializer(outputPath, currentTimestamp);
        }
        return instance;
    }


    @SneakyThrows
    public void outputToPath(int historyCount, int bugCount, Properties config, boolean enableProfiler) {
        // move all files in current dir
        var sourceDirectory = Paths.get(Config.DEFAULT_CURRENT_PATH);
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
        if (enableProfiler)
            outputMap.put("run_total_time", profiler.getTotalTime("run_total_time"));

        var objectMapper = new ObjectMapper();
        var json = objectMapper.writeValueAsString(outputMap);

        log.info("{}", json);
        var metadataPath = Paths.get(outputPath.toString(), "metadata.json");
        Files.writeString(metadataPath, json);
    }

    static Properties withoutSecrets(Properties config) {
        Properties archivedConfig = new Properties();
        archivedConfig.putAll(config);
        archivedConfig.remove(Config.DB_PASSWORD);
        return archivedConfig;
    }
}
