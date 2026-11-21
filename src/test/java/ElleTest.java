package checker.elle;

import config.Config;
import history.History;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElleTest {
    @Test
    void parseResultUsesLastExplicitBoolean() {
        assertTrue(Elle.parseResult("intermediate false\nfinal true\n", 0));
        assertFalse(Elle.parseResult("intermediate true\nfinal false\n", 0));
    }

    @Test
    void parseResultAcceptsViolationVerdict() {
        assertFalse(Elle.parseResult("elle-causal-invalid.edn \t false\n", 1));
    }

    @Test
    void parseResultRejectsMissingResultAndFailedProcess() {
        assertThrows(IllegalStateException.class, () -> Elle.parseResult("no verdict", 0));
        assertThrows(IllegalStateException.class, () -> Elle.parseResult("final true", 1));
        assertThrows(IllegalStateException.class, () -> Elle.parseResult("final false", 255));
    }

    @Test
    void profileIsEmptyBeforeVerification() {
        assertTrue(new Elle<>(new Properties()).getProfileInfo().isEmpty());
    }

    @Test
    void readFailureDestroysLiveProcess() {
        StubProcess process = new StubProcess("Stage construction Time: 999999999999999999999999 ms\n");
        Properties config = new Properties();
        config.setProperty(Config.HISTORY_TYPE, "elle");
        config.setProperty(Config.WORKLOAD_SKIP_GENERATION, "true");
        config.setProperty(Config.HISTORY_PATH, "unused.edn");
        Elle<Object, Object> elle = new Elle<>(config) {
            @Override
            Process startProcess(String path) {
                return process;
            }
        };

        assertThrows(NumberFormatException.class, () -> elle.verify(new History<>()));
        assertTrue(process.destroyed, "A live Elle process must be destroyed when output processing fails");
    }

    private static class StubProcess extends Process {
        private final String output;
        private boolean destroyed;

        private StubProcess(String output) {
            this.output = output;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }

        @Override
        public long pid() {
            return ProcessHandle.current().pid();
        }
    }
}
