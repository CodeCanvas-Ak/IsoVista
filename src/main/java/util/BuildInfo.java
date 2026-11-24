package util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Code identity written into every run manifest, so an artifact can be traced back to the
 * revision that produced it.
 */
public final class BuildInfo {

    private static final String REVISION = detectRevision();
    private static final String VERSION = detectVersion();

    private BuildInfo() {
    }

    public static String revision() {
        return REVISION;
    }

    public static String version() {
        return VERSION;
    }

    private static String detectRevision() {
        for (String name : new String[]{"ISOVISTA_COMMIT", "GIT_COMMIT", "SOURCE_COMMIT"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            try {
                Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.waitFor() == 0 && output.matches("[0-9a-f]{7,40}")) {
                    return output;
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                // fall through to unknown: git is optional at runtime
            }
        }
        return "unknown";
    }

    private static String detectVersion() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        try (InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/maven/org.example/DBTest/pom.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String pomVersion = properties.getProperty("version");
                if (pomVersion != null && !pomVersion.isBlank()) {
                    return pomVersion;
                }
            }
        } catch (IOException ignored) {
            // the manifest is optional
        }
        return "unknown";
    }
}
