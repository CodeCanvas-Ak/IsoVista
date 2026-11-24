package checker.result;

import checker.Checker;
import checker.IsolationLevel;
import exceptions.NotImplementedException;
import history.History;
import lombok.extern.slf4j.Slf4j;
import util.RuntimeInfoRecorder;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one checker and always produces a unified result. Verification failures that
 * are not semantic verdicts (timeout, out of memory, crash, incompatible input) are
 * kept as explicit statuses instead of being reported as accept or reject.
 */
@Slf4j
public final class CheckerRunner {

    private CheckerRunner() {
    }

    public static CheckerResult run(Class<? extends Checker> checkerClass, IsolationLevel isolation,
                                    String isolationConfig, Properties config, History history,
                                    String historyType, String historySha256, long timeoutMs) {
        Checker checker;
        try {
            checker = checkerClass.getDeclaredConstructor(Properties.class).newInstance(config);
        } catch (ReflectiveOperationException e) {
            CheckerResult failed = new CheckerResult();
            failed.setChecker(checkerName(checkerClass));
            failed.setIsolation(isolationConfig);
            failed.setResolvedIsolation(isolation == null ? isolationConfig : isolation.toString());
            failed.setTag(failed.getChecker() + "-" + failed.getResolvedIsolation());
            failed.setHistorySha256(historySha256);
            failed.setCounterexampleFormat("none");
            return fail(failed, e, "Cannot instantiate checker " + failed.getChecker());
        }
        return run(checker, isolation, isolationConfig, history, historyType, historySha256, timeoutMs);
    }

    public static CheckerResult run(Checker checker, IsolationLevel isolation,
                                    String isolationConfig, History history,
                                    String historyType, String historySha256, long timeoutMs) {
        String checkerName = checkerName(checker.getClass());
        String isolationName = isolation == null ? isolationConfig : isolation.toString();
        CheckerResult result = new CheckerResult();
        result.setChecker(checkerName);
        result.setIsolation(isolationConfig);
        result.setResolvedIsolation(isolationName);
        result.setTag(checkerName + "-" + isolationName);
        result.setHistorySha256(historySha256);
        result.setCounterexampleFormat("none");

        if (!checker.supportsHistoryType(historyType)) {
            result.setStatus(CheckerStatus.SKIPPED);
            result.setSkippedReason("checker does not support history type " + historyType);
            return result;
        }
        result.setCounterexampleFormat(checker.counterexampleFormat());

        long startedAt = System.nanoTime();
        try {
            Boolean verdict = timeoutMs > 0
                    ? verifyWithTimeout(checker, history, timeoutMs)
                    : checker.verify(history);
            result.setDurationMs(elapsedMs(startedAt));
            result.setStatus(Boolean.TRUE.equals(verdict) ? CheckerStatus.ACCEPT : CheckerStatus.REJECT);
            if (Boolean.TRUE.equals(verdict)) {
                result.setCounterexampleNote("no counterexample: history accepted");
            }
        } catch (TimeoutException e) {
            result.setDurationMs(elapsedMs(startedAt));
            result.setStatus(CheckerStatus.TIMEOUT);
            result.setError("verification exceeded " + timeoutMs + " ms");
            RuntimeInfoRecorder.destroyRegisteredProcesses();
        } catch (OutOfMemoryError e) {
            result.setDurationMs(elapsedMs(startedAt));
            result.setStatus(CheckerStatus.OOM);
            result.setError("out of memory: " + e.getMessage());
        } catch (Throwable e) {
            result.setDurationMs(elapsedMs(startedAt));
            fail(result, unwrap(e), "verification failed");
        } finally {
            result.setStagesMs(profileOf(checker));
        }
        return result;
    }

    /**
     * Archives the counterexample produced by a rejected verification.
     */
    public static String archiveCounterexample(CheckerResult result, Checker checker, Path bugDirectory) {
        try {
            checker.outputCounterexample(bugDirectory.toString());
            return archivedReference(result, bugDirectory);
        } catch (NotImplementedException e) {
            result.setCounterexampleFormat("none");
            result.setCounterexampleNote("checker does not implement counterexample output");
            return null;
        } catch (Throwable e) {
            log.warn("Failed to archive counterexample for {}: {}", result.getTag(), e.toString());
            result.setCounterexampleNote("counterexample output failed: " + e);
            return null;
        }
    }

    private static String archivedReference(CheckerResult result, Path bugDirectory) {
        String format = result.getCounterexampleFormat();
        if ("svg".equals(format)) {
            var files = bugDirectory.toFile().listFiles((dir, name) -> name.endsWith(".svg"));
            if (files == null || files.length == 0) {
                result.setCounterexampleNote("checker produced no SCC plot");
                return null;
            }
            String reference = files[0].getName();
            result.setCounterexampleRef(reference);
            result.setCounterexampleNote(files.length > 1 ? files.length + " SCC plots archived" : null);
            return reference;
        }
        if ("dot".equals(format)) {
            Path dot = bugDirectory.resolve("conflict.dot");
            if (!java.nio.file.Files.isRegularFile(dot)) {
                result.setCounterexampleNote("checker did not write a DOT file");
                return null;
            }
            result.setCounterexampleRef("conflict.dot");
            return "conflict.dot";
        }
        result.setCounterexampleNote("checker does not expose a counterexample graph");
        return null;
    }

    private static Boolean verifyWithTimeout(Checker checker, History history, long timeoutMs)
            throws TimeoutException {
        FutureTask<Boolean> task = new FutureTask<>(() -> checker.verify(history));
        Thread worker = new Thread(task, "checker-timeout-guard");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new TimeoutException("interrupted while waiting for the checker");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            if (!task.isDone()) {
                task.cancel(true);
            }
        }
    }

    private static CheckerResult fail(CheckerResult result, Throwable cause, String message) {
        result.setStatus(CheckerStatus.ERROR);
        result.setError(message + ": " + cause);
        log.warn("Checker {} failed: {}", result.getTag(), cause.toString());
        return result;
    }

    private static Map<String, Long> profileOf(Checker checker) {
        try {
            Map<String, Long> profile = checker.getProfileInfo();
            return profile == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profile);
        } catch (RuntimeException | Error e) {
            log.debug("Checker {} did not provide a profile: {}", checker.getClass().getSimpleName(), e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static String checkerName(Class<? extends Checker> checkerClass) {
        try {
            return (String) checkerClass.getDeclaredField("NAME").get(null);
        } catch (ReflectiveOperationException e) {
            return checkerClass.getSimpleName();
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof InvocationTargetException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
