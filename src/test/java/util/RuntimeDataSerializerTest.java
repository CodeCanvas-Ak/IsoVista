package util;

import config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDataSerializerTest {
    @TempDir
    Path tempDir;

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

    @Test
    void archivedConfigSanitizesCredentialsEmbeddedInDatabaseUrl() {
        String mysqlUrl = "jdbc:mysql://user:userinfo-secret@localhost:3306/db?user=user&password=query-secret&useSSL=false&pwd=pwd-secret&token=token-secret&passwordPolicy=keep";
        Properties runtimeConfig = new Properties();
        runtimeConfig.setProperty(Config.DB_URL, mysqlUrl);

        Properties archivedConfig = RuntimeDataSerializer.withoutSecrets(runtimeConfig);

        assertEquals("jdbc:mysql://user:<redacted>@localhost:3306/db?user=user&password=<redacted>&useSSL=false&pwd=<redacted>&token=<redacted>&passwordPolicy=keep",
                archivedConfig.getProperty(Config.DB_URL));
        assertEquals(mysqlUrl, runtimeConfig.getProperty(Config.DB_URL));

        runtimeConfig.setProperty(Config.DB_URL, "jdbc:h2:mem:test;USER=sa;PASSWORD=h2-secret;DB_CLOSE_DELAY=-1");
        archivedConfig = RuntimeDataSerializer.withoutSecrets(runtimeConfig);
        assertEquals("jdbc:h2:mem:test;USER=sa;PASSWORD=<redacted>;DB_CLOSE_DELAY=-1",
                archivedConfig.getProperty(Config.DB_URL));

        runtimeConfig.setProperty(Config.DB_URL, "jdbc:postgresql://localhost/db?access_token=access-secret&secret=shared-secret");
        archivedConfig = RuntimeDataSerializer.withoutSecrets(runtimeConfig);
        assertEquals("jdbc:postgresql://localhost/db?access_token=<redacted>&secret=<redacted>",
                archivedConfig.getProperty(Config.DB_URL));
    }

    @Test
    void outputToPathWritesSanitizedConfigToDisk() throws IOException {
        Path sourceDirectory = tempDir.resolve("current");
        Path outputPath = tempDir.resolve("run_1");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(outputPath);
        Files.writeString(sourceDirectory.resolve("output.log"), "test log");

        Properties runtimeConfig = new Properties();
        runtimeConfig.setProperty(Config.DB_URL, "jdbc:mysql://localhost:3306/db?password=url-secret&useSSL=false");
        runtimeConfig.setProperty(Config.DB_PASSWORD, "secret");
        runtimeConfig.setProperty(Config.DB_TYPE, "MYSQL");
        runtimeConfig.setProperty(Config.DB_ISOLATION, "SERIALIZABLE");
        runtimeConfig.setProperty(Config.CHECKER_ISOLATION, "SNAPSHOT_ISOLATION");

        RuntimeDataSerializer serializer = new RuntimeDataSerializer(outputPath, 1L);
        serializer.outputToPath(1, 0, runtimeConfig, false, sourceDirectory);

        Properties archivedConfig = new Properties();
        try (var input = Files.newInputStream(outputPath.resolve("config.properties"))) {
            archivedConfig.load(input);
        }
        assertNull(archivedConfig.getProperty(Config.DB_PASSWORD));
        assertEquals("jdbc:mysql://localhost:3306/db?password=<redacted>&useSSL=false",
                archivedConfig.getProperty(Config.DB_URL));
        assertEquals("jdbc:mysql://localhost:3306/db?password=url-secret&useSSL=false",
                runtimeConfig.getProperty(Config.DB_URL));
        assertEquals("secret", runtimeConfig.getProperty(Config.DB_PASSWORD));
        assertEquals("test log", Files.readString(outputPath.resolve("output.log")));
        assertTrue(Files.exists(outputPath.resolve("metadata.json")));
    }
}
