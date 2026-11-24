package util;

import config.Config;
import history.History;
import history.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HistoryFingerprintTest {
    @TempDir
    Path tempDir;

    private static History<Long, Long> history(long value) {
        History<Long, Long> history = new History<>();
        var session = history.addSession(0);
        var transaction = history.addTransaction(session, 0);
        transaction.setSuccess(true);
        history.addOperation(transaction, Operation.Type.WRITE, 1L, value);
        return history;
    }

    @Test
    void equalHistoriesProduceTheSameHash() {
        assertEquals(HistoryFingerprint.ofHistory(history(7), "text"),
                HistoryFingerprint.ofHistory(history(7), "text"));
    }

    @Test
    void differentHistoriesProduceDifferentHashes() {
        assertNotEquals(HistoryFingerprint.ofHistory(history(7), "text"),
                HistoryFingerprint.ofHistory(history(8), "text"));
    }

    @Test
    void configHashIgnoresPasswordsButKeepsEffectiveValues() {
        Properties first = new Properties();
        first.setProperty(Config.DB_TYPE, "MYSQL");
        first.setProperty(Config.DB_PASSWORD, "secret-a");
        first.setProperty(Config.WORKLOAD_SESSION, "4");

        Properties second = new Properties();
        second.setProperty(Config.DB_TYPE, "MYSQL");
        second.setProperty(Config.DB_PASSWORD, "secret-b");
        second.setProperty(Config.WORKLOAD_SESSION, "4");

        Properties third = new Properties();
        third.setProperty(Config.DB_TYPE, "MYSQL");
        third.setProperty(Config.DB_PASSWORD, "secret-a");
        third.setProperty(Config.WORKLOAD_SESSION, "8");

        assertEquals(HistoryFingerprint.ofProperties(first), HistoryFingerprint.ofProperties(second));
        assertNotEquals(HistoryFingerprint.ofProperties(first), HistoryFingerprint.ofProperties(third));
    }

    @Test
    void fileHashTracksContent() throws Exception {
        Path file = tempDir.resolve("history.txt");
        Files.writeString(file, "w(1,1,0,1)\n");
        String first = HistoryFingerprint.sha256(file);
        Files.writeString(file, "w(1,1,0,2)\n");
        assertNotEquals(first, HistoryFingerprint.sha256(file));
    }
}
