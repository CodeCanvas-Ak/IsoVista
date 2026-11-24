package regression;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Outcome of one declared expectation after running it against the current implementation.
 */
@Data
public class CaseResult {
    public static final String OK = "ok";
    public static final String MISMATCH = "mismatch";
    public static final String SKIPPED = "skipped";
    public static final String ERROR = "error";

    private String caseId;
    private String checker;
    private String isolation;
    private String expected;
    private String actual;
    private String outcome;
    private long durationMs;
    private String detail;
    private String historySha256;

    @JsonIgnore
    public boolean isConclusive() {
        return !SKIPPED.equals(outcome);
    }
}
