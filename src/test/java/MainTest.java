import config.Config;
import history.History;
import history.Operation;
import history.loader.TextHistoryLoader;
import history.loader.ElleHistoryLoader.ElleValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir
    Path tempDir;

    @Test
    void loadHistoryPreservesPathCase() throws IOException {
        Path historyDir = tempDir.resolve("MixedCase\u0130");
        Files.createDirectories(historyDir);
        Path historyPath = historyDir.resolve("History.txt");
        Files.writeString(historyPath, "w(1,1,0,0)\nr(1,1,0,0)\n");

        Properties config = new Properties();
        config.setProperty(Config.HISTORY_PATH, historyPath.toString());
        config.setProperty(Config.HISTORY_TYPE, "TEXT");

        History history = Main.loadHistory(config);

        assertEquals(2, history.getOperations().size());
    }

    @Test
    void outputBugHistoryComparesHistoryTypeByValue() {
        History<Long, Long> history = new History<>();
        var session = history.addSession(0);
        var transaction = history.addTransaction(session, 0);
        history.addOperation(transaction, Operation.Type.WRITE, 1L, 1L);

        Properties config = new Properties();
        config.setProperty(Config.HISTORY_TYPE, new String("TEXT"));

        Main.outputBugHistory(history, config, tempDir);

        Path outputPath = tempDir.resolve("bug_hist.txt");
        assertTrue(Files.exists(outputPath));
        History<Long, Long> loadedHistory = new TextHistoryLoader().loadHistory(outputPath.toString());
        assertEquals(transaction.getOps(), loadedHistory.getTransaction(0).getOps());
    }

    @Test
    void outputBugHistorySupportsElle() {
        History<Integer, ElleValue> history = new History<>();
        Properties config = new Properties();
        config.setProperty(Config.HISTORY_TYPE, "ELLE");

        Main.outputBugHistory(history, config, tempDir);

        assertTrue(Files.exists(tempDir.resolve("bug_hist.edn")));
    }

    @Test
    void missingDatabaseTypeFailsWithClearConfigError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Main().run(new Properties()));

        assertEquals("Missing required configuration: db.type", error.getMessage());
    }

    @Test
    void unknownDatabaseFailsBeforeCheckerInitialization() {
        Properties config = new Properties();
        config.setProperty(Config.DB_TYPE, "not-a-database");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Main().run(config));

        assertTrue(error.getMessage().contains("Unknown database type"));
    }
}
