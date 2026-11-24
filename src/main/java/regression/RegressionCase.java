package regression;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A regression case as declared by {@code benchmarks/regression/<case-id>/case.yaml}.
 */
@Data
public class RegressionCase {
    private String id;
    private String kind;
    private String model;
    private String history;
    private Map<String, Object> source;
    private List<String> anomalies;
    private List<String> assumptions;
    private List<RegressionCheck> checks = new ArrayList<>();
    private String notes;

    public List<String> anomaliesOrEmpty() {
        return anomalies == null ? new ArrayList<>() : anomalies;
    }

    public List<String> assumptionsOrEmpty() {
        return assumptions == null ? new ArrayList<>() : assumptions;
    }
}
