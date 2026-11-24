package regression;

import checker.Checker;
import checker.IsolationLevel;
import checker.result.CheckerResult;
import checker.result.CheckerRunner;
import config.Config;
import history.History;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.reflections.Reflections;
import org.yaml.snakeyaml.Yaml;
import util.ConfigParser;
import util.HistoryFingerprint;
import util.HistoryLoaderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the regression suite declared under {@code benchmarks/regression/}. Each case is a
 * directory with a {@code case.yaml} and its history; every declared check is executed
 * against the current implementation and compared with the expected verdict.
 *
 * <p>Checks whose runtime requirements are missing are reported as {@code skipped}, never as
 * a failure, so a partial environment still produces a usable result.</p>
 */
@Slf4j
public final class RegressionSuite {

    private static final Map<String, Boolean> REQUIREMENTS = new ConcurrentHashMap<>();
    private static volatile Map<String, Class<? extends Checker>> CHECKERS;

    private RegressionSuite() {
    }

    public static void main(String[] args) {
        Path root = Paths.get("benchmarks", "regression");
        boolean probe = false;
        String only = null;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--probe".equals(argument)) {
                probe = true;
            } else if ("--only".equals(argument) && i + 1 < args.length) {
                only = args[++i];
            } else {
                root = Paths.get(argument);
            }
        }
        List<CaseResult> results = run(root, probe, only);
        System.out.print(render(results));
        long bad = results.stream().filter(result -> !CaseResult.OK.equals(result.getOutcome())
                && !CaseResult.SKIPPED.equals(result.getOutcome())).count();
        if (bad > 0) {
            System.exit(1);
        }
    }

    public static List<CaseResult> run(Path root) {
        return run(root, false, null);
    }

    public static List<CaseResult> run(Path root, boolean probe) {
        return run(root, probe, null);
    }

    public static List<CaseResult> run(Path root, boolean probe, String only) {
        List<CaseResult> results = new ArrayList<>();
        List<Path> caseDirectories;
        try (var directories = Files.list(root)) {
            caseDirectories = directories.filter(Files::isDirectory).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read regression suite at " + root, e);
        }
        for (Path caseDirectory : caseDirectories) {
            if (only != null && !caseDirectory.getFileName().toString().contains(only)) {
                continue;
            }
            Path manifest = caseDirectory.resolve("case.yaml");
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            RegressionCase regressionCase = loadCase(manifest);
            results.addAll(runCase(caseDirectory, regressionCase, probe));
        }
        results.sort(Comparator.comparing(CaseResult::getCaseId)
                .thenComparing(CaseResult::getChecker)
                .thenComparing(CaseResult::getIsolation));
        return results;
    }

    public static RegressionCase loadCase(Path manifest) {
        try (InputStream in = Files.newInputStream(manifest)) {
            Map<String, Object> raw = new Yaml().load(in);
            RegressionCase regressionCase = new RegressionCase();
            regressionCase.setId(String.valueOf(raw.get("id")));
            regressionCase.setKind(String.valueOf(raw.get("kind")));
            regressionCase.setModel(String.valueOf(raw.get("model")));
            regressionCase.setHistory(String.valueOf(raw.get("history")));
            regressionCase.setNotes(raw.get("notes") == null ? null : String.valueOf(raw.get("notes")));
            Object anomalies = raw.get("anomalies");
            if (anomalies instanceof List) {
                regressionCase.setAnomalies(((List<?>) anomalies).stream().map(String::valueOf).collect(Collectors.toList()));
            }
            Object assumptions = raw.get("assumptions");
            if (assumptions instanceof List) {
                regressionCase.setAssumptions(((List<?>) assumptions).stream().map(String::valueOf).collect(Collectors.toList()));
            }
            Object source = raw.get("source");
            if (source instanceof Map) {
                Map<String, Object> sourceMap = new HashMap<>();
                ((Map<?, ?>) source).forEach((key, value) -> sourceMap.put(String.valueOf(key), value));
                regressionCase.setSource(sourceMap);
            }
            Object checks = raw.get("checks");
            if (!(checks instanceof List)) {
                throw new IllegalStateException("Regression case " + manifest + " declares no checks");
            }
            for (Object entry : (List<?>) checks) {
                Map<?, ?> checkMap = (Map<?, ?>) entry;
                RegressionCheck check = new RegressionCheck();
                check.setChecker(String.valueOf(checkMap.get("checker")));
                check.setIsolation(String.valueOf(checkMap.get("isolation")));
                check.setVerdict(checkMap.get("verdict") == null ? null : String.valueOf(checkMap.get("verdict")));
                Object requires = checkMap.get("requires");
                if (requires instanceof List) {
                    check.setRequires(((List<?>) requires).stream().map(String::valueOf).collect(Collectors.toList()));
                }
                Object timeout = checkMap.get("timeout_ms");
                check.setTimeoutMs(timeout == null ? 0L : Long.parseLong(String.valueOf(timeout)));
                regressionCase.getChecks().add(check);
            }
            return regressionCase;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read regression case " + manifest, e);
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 300_000;
    private static final long STARTUP_GRACE_MS = 15_000;

    private static List<CaseResult> runCase(Path caseDirectory, RegressionCase regressionCase, boolean probe) {
        List<CaseResult> results = new ArrayList<>();
        for (int index = 0; index < regressionCase.getChecks().size(); index++) {
            results.add(forkCheck(caseDirectory, regressionCase, index, probe));
        }
        return results;
    }

    /**
     * Runs one check in a forked JVM. The native solvers used by the SMT checkers keep
     * process wide state and are not safe to run twice in one JVM, and a native crash would
     * otherwise kill the whole suite. A check that cannot be forked falls back to the
     * current JVM.
     */
    private static CaseResult forkCheck(Path caseDirectory, RegressionCase regressionCase, int index, boolean probe) {
        RegressionCheck check = regressionCase.getChecks().get(index);
        CaseResult result = new CaseResult();
        result.setCaseId(regressionCase.getId());
        result.setChecker(check.getChecker());
        result.setIsolation(check.getIsolation());
        result.setExpected(probe ? "-" : check.getVerdict());
        long timeoutMs = check.getTimeoutMs() > 0 ? check.getTimeoutMs() : DEFAULT_TIMEOUT_MS;

        String javaExecutable = javaExecutable();
        String classpath = System.getProperty("java.class.path");
        if (javaExecutable == null || classpath == null || classpath.isBlank()) {
            return executeCheck(caseDirectory, regressionCase, check, probe);
        }

        Path output = null;
        Path errors = null;
        try {
            output = Files.createTempFile("isovista-regression-", ".out");
            errors = Files.createTempFile("isovista-regression-", ".err");
            Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, CheckExecutor.class.getName(),
                    caseDirectory.toString(), String.valueOf(index), probe ? "--probe" : "--run")
                    .redirectOutput(output.toFile())
                    .redirectError(errors.toFile())
                    .start();
            long startedAt = System.nanoTime();
            boolean finished = process.waitFor(timeoutMs + STARTUP_GRACE_MS, TimeUnit.MILLISECONDS);
            result.setDurationMs((System.nanoTime() - startedAt) / 1_000_000L);
            if (!finished) {
                process.destroyForcibly();
                result.setActual("timeout");
                result.setOutcome(CaseResult.ERROR);
                result.setDetail("checker process did not finish within " + timeoutMs + " ms");
                return result;
            }
            CaseResult parsed = parseResult(output);
            if (parsed != null) {
                parsed.setDurationMs(result.getDurationMs());
                return parsed;
            }
            result.setActual("error");
            result.setOutcome(CaseResult.ERROR);
            result.setDetail("checker process exited with code " + process.exitValue()
                    + tailOf(errors));
            return result;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Cannot fork a regression check, running it in process: {}", e.toString());
            return executeCheck(caseDirectory, regressionCase, check, probe);
        } finally {
            deleteQuietly(output);
            deleteQuietly(errors);
        }
    }

    private static CaseResult parseResult(Path output) throws IOException {
        if (!Files.isRegularFile(output)) {
            return null;
        }
        String content = Files.readString(output);
        int marker = content.lastIndexOf(CheckExecutor.RESULT_PREFIX);
        if (marker < 0) {
            return null;
        }
        String json = content.substring(marker + CheckExecutor.RESULT_PREFIX.length()).trim();
        int endOfLine = json.indexOf('\n');
        if (endOfLine >= 0) {
            json = json.substring(0, endOfLine);
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, CaseResult.class);
    }

    private static String tailOf(Path errors) throws IOException {
        if (errors == null || !Files.isRegularFile(errors)) {
            return "";
        }
        List<String> lines = Files.readAllLines(errors);
        if (lines.isEmpty()) {
            return "";
        }
        return " | " + String.join(" / ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temporary output of a forked check
        }
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "java.exe" : "java";
        return Paths.get(javaHome, "bin", executable).toString();
    }

    static CaseResult executeCheck(Path caseDirectory, RegressionCase regressionCase, RegressionCheck check,
                                   boolean probe) {
        Path historyPath = caseDirectory.resolve(regressionCase.getHistory());
        CaseResult result = new CaseResult();
        result.setCaseId(regressionCase.getId());
        result.setChecker(check.getChecker());
        result.setIsolation(check.getIsolation());
        result.setExpected(probe ? "-" : check.getVerdict());

        if (!Files.isRegularFile(historyPath)) {
            result.setActual("error");
            result.setOutcome(CaseResult.ERROR);
            result.setDetail("missing history " + historyPath);
            return result;
        }
        String historyType = historyPath.toString().toLowerCase(Locale.ROOT).endsWith(".edn") ? "elle" : Config.DEFAULT_HISTORY_TYPE;
        History history;
        try {
            history = HistoryLoaderFactory.getHistoryLoader(historyType).loadHistory(historyPath.toString());
        } catch (RuntimeException e) {
            result.setActual("error");
            result.setOutcome(CaseResult.ERROR);
            result.setDetail("cannot load history: " + e.getMessage());
            return result;
        }
        String historySha256 = HistoryFingerprint.ofHistory(history, historyType);
        result.setHistorySha256(historySha256);

        List<String> missing = check.requiresOrEmpty().stream()
                .filter(requirement -> !available(requirement)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            result.setActual("-");
            result.setOutcome(CaseResult.SKIPPED);
            result.setDetail("requires " + String.join(", ", missing) + " (unavailable)");
            return result;
        }

        Pair<String, IsolationLevel> resolved = ConfigParser.parseIsolationConfig(check.getIsolation());
        if (!resolved.getLeft().equalsIgnoreCase(check.getChecker())) {
            result.setActual("-");
            result.setOutcome(CaseResult.ERROR);
            result.setDetail("isolation " + check.getIsolation() + " resolves to checker " + resolved.getLeft()
                    + ", not " + check.getChecker());
            return result;
        }
        Class<? extends Checker> checkerClass = checkerRegistry().get(resolved.getLeft().toLowerCase(Locale.ROOT));
        if (checkerClass == null) {
            result.setActual("-");
            result.setOutcome(CaseResult.ERROR);
            result.setDetail("checker " + resolved.getLeft() + " is not registered");
            return result;
        }

        Properties config = new Properties();
        config.setProperty(Config.HISTORY_TYPE, historyType);
        config.setProperty(Config.HISTORY_PATH, historyPath.toString());
        config.setProperty(Config.CHECKER_ISOLATION, resolved.getRight().toString());

        CheckerResult checkerResult = CheckerRunner.run(checkerClass, resolved.getRight(), check.getIsolation(),
                config, history, historyType, historySha256, check.getTimeoutMs());
        result.setDurationMs(checkerResult.getDurationMs());
        result.setActual(checkerResult.getStatus().value());
        result.setDetail(checkerResult.getError() != null ? checkerResult.getError() : checkerResult.getSkippedReason());

        if (!checkerResult.getStatus().isVerdict()) {
            result.setOutcome(CaseResult.ERROR);
            return result;
        }
        if (probe) {
            result.setOutcome(CaseResult.OK);
            return result;
        }
        result.setOutcome(check.getVerdict().equalsIgnoreCase(result.getActual()) ? CaseResult.OK : CaseResult.MISMATCH);
        return result;
    }

    public static String render(List<CaseResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("%-26s %-32s %-8s %-8s %-9s %8s%n",
                "case", "checker", "expect", "actual", "outcome", "ms"));
        for (CaseResult result : results) {
            builder.append(String.format("%-26s %-32s %-8s %-8s %-9s %8d",
                    result.getCaseId(), result.getChecker() + "-" + result.getIsolation(),
                    result.getExpected(), result.getActual(), result.getOutcome(), result.getDurationMs()));
            if (result.getDetail() != null && !CaseResult.OK.equals(result.getOutcome())) {
                builder.append("  ").append(result.getDetail());
            }
            builder.append('\n');
        }
        long ok = results.stream().filter(result -> CaseResult.OK.equals(result.getOutcome())).count();
        long skipped = results.stream().filter(result -> CaseResult.SKIPPED.equals(result.getOutcome())).count();
        long mismatch = results.stream().filter(result -> CaseResult.MISMATCH.equals(result.getOutcome())).count();
        long error = results.stream().filter(result -> CaseResult.ERROR.equals(result.getOutcome())).count();
        builder.append(String.format("total=%d ok=%d skipped=%d mismatch=%d error=%d%n",
                results.size(), ok, skipped, mismatch, error));
        return builder.toString();
    }

    private static Map<String, Class<? extends Checker>> checkerRegistry() {
        Map<String, Class<? extends Checker>> registry = CHECKERS;
        if (registry == null) {
            synchronized (RegressionSuite.class) {
                registry = CHECKERS;
                if (registry == null) {
                    Map<String, Class<? extends Checker>> built = new HashMap<>();
                    for (Class<? extends Checker> checkerClass : new Reflections("checker").getSubTypesOf(Checker.class)) {
                        try {
                            String name = (String) checkerClass.getDeclaredField("NAME").get(null);
                            built.put(name.toLowerCase(Locale.ROOT), checkerClass);
                        } catch (ReflectiveOperationException e) {
                            log.debug("Skip checker without a NAME constant: {}", checkerClass.getName());
                        }
                    }
                    CHECKERS = built;
                    registry = built;
                }
            }
        }
        return registry;
    }

    /**
     * Runtime requirement of a check. Unknown requirements are treated as unavailable, so a
     * typo cannot silently turn a required dependency into a passing check.
     */
    static boolean available(String requirement) {
        return REQUIREMENTS.computeIfAbsent(requirement, RegressionSuite::checkRequirement);
    }

    private static boolean checkRequirement(String requirement) {
        switch (requirement) {
            case "native-monosat":
                try {
                    System.loadLibrary("monosat");
                    return true;
                } catch (UnsatisfiedLinkError | RuntimeException e) {
                    log.info("Requirement {} unavailable: {}", requirement, e.toString());
                    return false;
                }
            case "python3":
                return commandSucceeds("python3", "--version");
            case "viper":
                return commandSucceeds("python3", "-c", "import networkx, numpy, yaml");
            case "elle-cli":
                return Files.isRegularFile(Paths.get("third_party", "elle-cli-0.1.7-standalone.jar"));
            default:
                log.warn("Unknown regression requirement {}", requirement);
                return false;
        }
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            boolean ok = process.waitFor() == 0;
            if (!ok) {
                log.info("Requirement probe {} failed: {}", String.join(" ", command),
                        new String(output, StandardCharsets.UTF_8).trim());
            }
            return ok;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
