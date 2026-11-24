package checker.result;

import checker.Checker;
import checker.IsolationLevel;
import exceptions.NotImplementedException;
import history.History;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckerRunnerTest {
    @TempDir
    Path tempDir;

    private static class StubChecker implements Checker<Object, Object> {
        private final boolean verdict;
        private long sleepMs;
        private Throwable failure;
        private boolean supported = true;
        private boolean dotImplemented = true;
        private boolean profileFails;
        private String format = "dot";

        private StubChecker(boolean verdict) {
            this.verdict = verdict;
        }

        @Override
        public boolean verify(History<Object, Object> history) {
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            return verdict;
        }

        @Override
        public void outputDotFile(String path) {
            if (!dotImplemented) {
                throw new NotImplementedException();
            }
            try {
                Files.writeString(Path.of(path), "digraph { a -> b }");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Map<String, Long> getProfileInfo() {
            if (profileFails) {
                throw new IllegalStateException("no profile");
            }
            return Map.of("Construction", 5L);
        }

        @Override
        public boolean supportsHistoryType(String historyType) {
            return supported;
        }

        @Override
        public String counterexampleFormat() {
            return format;
        }
    }

    private CheckerResult run(StubChecker checker, long timeoutMs) {
        return CheckerRunner.run(checker, IsolationLevel.SNAPSHOT_ISOLATION, "SNAPSHOT_ISOLATION",
                new History<>(), "text", "abc123", timeoutMs);
    }

    @Test
    void acceptAndRejectAreVerdicts() {
        assertEquals(CheckerStatus.ACCEPT, run(new StubChecker(true), 0).getStatus());
        CheckerResult reject = run(new StubChecker(false), 0);
        assertEquals(CheckerStatus.REJECT, reject.getStatus());
        assertEquals("abc123", reject.getHistorySha256());
        assertEquals("SNAPSHOT_ISOLATION", reject.getIsolation());
        assertEquals(5L, reject.getStagesMs().get("Construction"));
    }

    @Test
    void timeoutIsNotReportedAsAVerdict() {
        StubChecker checker = new StubChecker(true);
        checker.sleepMs = 5_000;

        CheckerResult result = run(checker, 100);

        assertEquals(CheckerStatus.TIMEOUT, result.getStatus());
        assertTrue(result.getDurationMs() >= 100);
        assertNotNull(result.getError());
    }

    @Test
    void outOfMemoryIsReportedSeparately() {
        StubChecker checker = new StubChecker(true);
        checker.failure = new OutOfMemoryError("boom");

        assertEquals(CheckerStatus.OOM, run(checker, 0).getStatus());
    }

    @Test
    void crashIsAnErrorAndKeepsTheCause() {
        StubChecker checker = new StubChecker(true);
        checker.failure = new IllegalStateException("monosat missing");

        CheckerResult result = run(checker, 0);
        assertEquals(CheckerStatus.ERROR, result.getStatus());
        assertTrue(result.getError().contains("monosat missing"));
    }

    @Test
    void incompatibleHistoryIsSkippedBeforeVerification() {
        StubChecker checker = new StubChecker(true);
        checker.supported = false;

        CheckerResult result = run(checker, 0);
        assertEquals(CheckerStatus.SKIPPED, result.getStatus());
        assertTrue(result.getSkippedReason().contains("text"));
        assertNull(result.getError());
    }

    @Test
    void profileFailureDoesNotTurnAVerdictIntoAnError() {
        StubChecker checker = new StubChecker(true);
        checker.profileFails = true;

        CheckerResult result = run(checker, 0);
        assertEquals(CheckerStatus.ACCEPT, result.getStatus());
        assertTrue(result.getStagesMs().isEmpty());
    }

    @Test
    void counterexampleIsArchivedAndReferenced() {
        StubChecker checker = new StubChecker(false);
        CheckerResult result = run(checker, 0);

        String reference = CheckerRunner.archiveCounterexample(result, checker, tempDir);

        assertEquals("conflict.dot", reference);
        assertEquals("conflict.dot", result.getCounterexampleRef());
        assertTrue(Files.isRegularFile(tempDir.resolve("conflict.dot")));
    }

    @Test
    void missingCounterexampleOutputIsRecordedNotFabricated() {
        StubChecker checker = new StubChecker(false);
        checker.dotImplemented = false;
        CheckerResult result = run(checker, 0);

        String reference = CheckerRunner.archiveCounterexample(result, checker, tempDir);

        assertNull(reference);
        assertEquals("none", result.getCounterexampleFormat());
        assertNotNull(result.getCounterexampleNote());
        assertFalse(Files.exists(tempDir.resolve("conflict.dot")));
    }
}
