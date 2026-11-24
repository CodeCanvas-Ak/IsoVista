package util;

import checker.result.CheckerResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Run level manifest content. Every run writes this next to {@code metadata.json}, so an
 * artifact can be traced to its input, configuration and code revision.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RunRecord {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private String runId;
    private String status = "completed";
    private String startedAt;
    private String endedAt;
    private String codeRevision;
    private String codeVersion;
    private String configSha256;
    private String historyPath;
    private String historyType;
    private String historySha256;
    private List<String> historyHashes = new ArrayList<>();
    private String historySourceSha256;
    private Map<String, Object> environment = new LinkedHashMap<>();
    private List<CheckerResult> checkerResults = new ArrayList<>();
    private List<String> artifacts = new ArrayList<>();

    /**
     * Environment block written into {@code run.json}. Only stable facts are recorded:
     * no host names, user directories or credentials.
     */
    public static Map<String, Object> environment() {
        var runtime = Runtime.getRuntime();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java_version", System.getProperty("java.version"));
        environment.put("java_vm", System.getProperty("java.vm.name"));
        environment.put("os_name", System.getProperty("os.name"));
        environment.put("os_arch", System.getProperty("os.arch"));
        environment.put("os_version", System.getProperty("os.version"));
        environment.put("available_processors", runtime.availableProcessors());
        environment.put("max_memory_mb", runtime.maxMemory() / (1024 * 1024));
        environment.put("timezone", TimeZone.getDefault().getID());
        return environment;
    }
}
