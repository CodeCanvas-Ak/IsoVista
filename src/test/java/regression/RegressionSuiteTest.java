package regression;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegressionSuiteTest {

    @Test
    void defaultSuiteMatchesDeclaredVerdicts() {
        var root = Paths.get("benchmarks", "regression");
        Assumptions.assumeTrue(Files.isDirectory(root), "the regression suite is not available");

        List<CaseResult> results = RegressionSuite.run(root);
        System.out.print(RegressionSuite.render(results));

        List<CaseResult> conclusive = results.stream().filter(CaseResult::isConclusive).collect(Collectors.toList());
        assertFalse(conclusive.isEmpty(), "no regression check could run in this environment");
        List<CaseResult> unexpected = conclusive.stream()
                .filter(result -> !CaseResult.OK.equals(result.getOutcome()))
                .collect(Collectors.toList());
        assertTrue(unexpected.isEmpty(), () -> RegressionSuite.render(unexpected));
    }

    @Test
    void casesDeclareTheMinimumContract() {
        var root = Paths.get("benchmarks", "regression");
        Assumptions.assumeTrue(Files.isDirectory(root), "the regression suite is not available");

        try (var directories = Files.list(root)) {
            for (var caseDirectory : directories.filter(Files::isDirectory).collect(Collectors.toList())) {
                var manifest = caseDirectory.resolve("case.yaml");
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                RegressionCase regressionCase = RegressionSuite.loadCase(manifest);
                assertFalse(regressionCase.getId().isBlank(), "case id is required");
                assertTrue(Files.isRegularFile(caseDirectory.resolve(regressionCase.getHistory())),
                        regressionCase.getId() + " points to a missing history");
                assertFalse(regressionCase.getChecks().isEmpty(), regressionCase.getId() + " has no checks");
                assertTrue(regressionCase.anomaliesOrEmpty().stream().anyMatch(anomaly -> true)
                                == regressionCase.getChecks().stream()
                                .anyMatch(check -> "reject".equalsIgnoreCase(check.getVerdict())),
                        regressionCase.getId() + " must declare anomalies exactly when it expects a rejection");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
