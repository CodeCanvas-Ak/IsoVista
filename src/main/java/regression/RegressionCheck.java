package regression;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One declared expectation of a regression case: which checker, which isolation spec and
 * which verdict the implementation must produce for the case history.
 */
@Data
public class RegressionCheck {
    private String checker;
    private String isolation;
    private String verdict;
    private List<String> requires = new ArrayList<>();
    private long timeoutMs;

    public List<String> requiresOrEmpty() {
        return requires == null ? new ArrayList<>() : requires;
    }
}
