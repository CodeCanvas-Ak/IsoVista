import history.History;
import history.loader.TextHistoryLoader;
import history.serializer.TextHistorySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HistoryLoaderSerializerTest {
    @Test
    public void testHistoryLoaderAndSerializer(@TempDir Path tempDir) {
        String currentDirectory = System.getProperty("user.dir") + "/src/test/java/resources/";
        String tempFilePath = tempDir.resolve("round_trip_history.txt").toString();

        TextHistoryLoader historyLoader = new TextHistoryLoader();
        History<Long, Long> history = historyLoader.loadHistory(currentDirectory + "sample_history.txt");
        history.addAbortedWrite(42L, 7L);

        TextHistorySerializer historySerializer = new TextHistorySerializer();
        historySerializer.serializeHistory(history, tempFilePath);

        History<Long, Long> loadedHistory = historyLoader.loadHistory(tempFilePath);

        assertEquals(history, loadedHistory);
        assertEquals(history.getAbortedWrites(), loadedHistory.getAbortedWrites());
        assertEquals(history.getTransactions().keySet(), loadedHistory.getTransactions().keySet());
        for (long txnId : history.getTransactions().keySet()) {
            assertEquals(history.getTransaction(txnId).getOps(), loadedHistory.getTransaction(txnId).getOps());
        }
    }

    @Test
    public void invalidHistoryReportsLineNumber(@TempDir Path tempDir) throws IOException {
        Path historyPath = tempDir.resolve("invalid_history.txt");
        Files.writeString(historyPath, "w(1,1,0,0)\nnot-a-history\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new TextHistoryLoader().loadHistory(historyPath.toString()));

        assertTrue(error.getMessage().contains("line 2"));
    }
}
