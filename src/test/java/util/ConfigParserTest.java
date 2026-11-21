package util;

import checker.IsolationLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigParserTest {
    @Test
    void elleTccUsesCausalConsistencyIsolation() {
        var parsed = ConfigParser.parseIsolationConfig("ELLE_TRANSACTIONAL_CAUSAL_CONSISTENCY");

        assertEquals("ELLE_TCC", parsed.getLeft());
        assertEquals(IsolationLevel.CAUSAL_CONSISTENCY, parsed.getRight());
    }
}
