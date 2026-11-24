package regression;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of one forked regression check. The suite forks a JVM per check so a native
 * solver crash, an out of memory or a hung checker cannot take the whole suite down.
 */
public final class CheckExecutor {

    public static final String RESULT_PREFIX = "@@RESULT@@";

    private CheckExecutor() {
    }

    public static void main(String[] args) throws Exception {
        Path caseDirectory = Paths.get(args[0]);
        int checkIndex = Integer.parseInt(args[1]);
        boolean probe = args.length > 2 && "--probe".equals(args[2]);
        RegressionCase regressionCase = RegressionSuite.loadCase(caseDirectory.resolve("case.yaml"));
        RegressionCheck check = regressionCase.getChecks().get(checkIndex);
        CaseResult result = RegressionSuite.executeCheck(caseDirectory, regressionCase, check, probe);
        System.out.println(RESULT_PREFIX + new ObjectMapper().writeValueAsString(result));
    }
}
