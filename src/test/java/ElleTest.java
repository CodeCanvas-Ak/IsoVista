package checker.elle;

import org.junit.jupiter.api.Test;

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
    void parseResultRejectsMissingResultAndFailedProcess() {
        assertThrows(IllegalStateException.class, () -> Elle.parseResult("no verdict", 0));
        assertThrows(IllegalStateException.class, () -> Elle.parseResult("final true", 1));
    }

    @Test
    void profileIsEmptyBeforeVerification() {
        assertTrue(new Elle<>(new Properties()).getProfileInfo().isEmpty());
    }
}
