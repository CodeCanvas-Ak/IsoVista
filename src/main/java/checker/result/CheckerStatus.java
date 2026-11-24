package checker.result;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Unified checker outcome. Only {@link #ACCEPT} and {@link #REJECT} are semantic
 * verdicts; every other value is a non-conclusive status that must not be counted
 * as a verification result.
 */
public enum CheckerStatus {
    ACCEPT,
    REJECT,
    TIMEOUT,
    OOM,
    ERROR,
    SKIPPED;

    public boolean isVerdict() {
        return this == ACCEPT || this == REJECT;
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
