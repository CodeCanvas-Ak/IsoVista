package util;

import config.Config;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeDataSerializerTest {
    @Test
    void archivedConfigRemovesPasswordWithoutMutatingRuntimeConfig() {
        Properties runtimeConfig = new Properties();
        runtimeConfig.setProperty(Config.DB_URL, "jdbc:mysql://localhost:3306/");
        runtimeConfig.setProperty(Config.DB_PASSWORD, "secret");

        Properties archivedConfig = RuntimeDataSerializer.withoutSecrets(runtimeConfig);

        assertNull(archivedConfig.getProperty(Config.DB_PASSWORD));
        assertEquals("jdbc:mysql://localhost:3306/", archivedConfig.getProperty(Config.DB_URL));
        assertEquals("secret", runtimeConfig.getProperty(Config.DB_PASSWORD));
    }
}
