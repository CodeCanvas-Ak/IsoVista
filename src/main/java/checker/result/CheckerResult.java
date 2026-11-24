package checker.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a single checker run, following the unified schema of the checker plan.
 * Non-verdict statuses never carry a made-up duration of zero: {@code durationMs}
 * keeps the observed wall-clock time and {@code stagesMs} keeps only measured stages.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CheckerResult {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private String checker;
    private String isolation;
    private String resolvedIsolation;
    private String tag;
    private String historySha256;
    private CheckerStatus status = CheckerStatus.ERROR;
    private String counterexampleRef;
    private String counterexampleFormat;
    private String counterexampleNote;
    private Map<String, Long> stagesMs = new LinkedHashMap<>();
    private List<String> assumptions = new ArrayList<>();
    private String error;
    private String skippedReason;
    private long durationMs;

    public static CheckerResult skipped(String checker, String isolation, String historySha256, String reason) {
        CheckerResult result = new CheckerResult();
        result.setChecker(checker);
        result.setIsolation(isolation);
        result.setResolvedIsolation(isolation);
        result.setTag(checker + "-" + isolation);
        result.setHistorySha256(historySha256);
        result.setStatus(CheckerStatus.SKIPPED);
        result.setSkippedReason(reason);
        result.setCounterexampleFormat("none");
        return result;
    }

    @JsonIgnore
    public boolean isVerdict() {
        return status != null && status.isVerdict();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", schemaVersion);
        map.put("checker", checker);
        map.put("isolation", isolation);
        map.put("resolved_isolation", resolvedIsolation);
        map.put("history_sha256", historySha256);
        map.put("status", status == null ? CheckerStatus.ERROR.value() : status.value());
        map.put("counterexample_ref", counterexampleRef);
        map.put("counterexample_format", counterexampleFormat);
        map.put("counterexample_note", counterexampleNote);
        map.put("stages_ms", stagesMs);
        map.put("assumptions", assumptions);
        map.put("error", error);
        map.put("skipped_reason", skippedReason);
        map.put("duration_ms", durationMs);
        return map;
    }
}
