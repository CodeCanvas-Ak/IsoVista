package checker;

import history.History;

import java.nio.file.Paths;
import java.util.Map;

public interface Checker<KeyType, ValType> {
    /**
     * Verify a history.
     *
     * @param history the history to verify
     * @return true if the history is valid, false otherwise
     */
    boolean verify(History<KeyType, ValType> history);

    /**
     * Outputs a dot file to the given path.
     *
     * @param  path  the path where the dot file will be outputted
     */
    void outputDotFile(String path);

    /**
     * Retrieves profile information.
     *
     * @return         Profile information as a map with tags as keys and time(ms) as values
     */
    Map<String, Long> getProfileInfo();

    /**
     * Whether this checker can consume the given history type. A checker that only
     * supports a subset must override this, so the runner records {@code skipped}
     * instead of turning an incompatible input into a verification failure.
     */
    default boolean supportsHistoryType(String historyType) {
        return true;
    }

    /**
     * Format of the archived counterexample: {@code dot}, {@code svg} or {@code none}.
     */
    default String counterexampleFormat() {
        return "dot";
    }

    /**
     * Archives the counterexample of the last verification into the given directory.
     *
     * @param directory directory that receives the counterexample artifact
     */
    default void outputCounterexample(String directory) {
        outputDotFile(Paths.get(directory, "conflict.dot").toString());
    }
}
