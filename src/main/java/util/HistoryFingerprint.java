package util;

import history.History;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/**
 * Content hashes used by run manifests and checker results. A history hash identifies the
 * exact input a checker consumed, so two checkers can be compared on the same history.
 */
public final class HistoryFingerprint {

    private HistoryFingerprint() {
    }

    public static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return sha256(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot hash file " + path, e);
        }
    }

    public static String sha256(byte[] content) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] digest) {
        var builder = new StringBuilder(digest.length * 2);
        for (var value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Deterministic hash of the archived configuration: keys are sorted and secrets are
     * removed, so the same effective configuration always produces the same fingerprint.
     */
    public static String ofProperties(Properties config) {
        Properties archived = RuntimeDataSerializer.withoutSecrets(config);
        StringBuilder content = new StringBuilder();
        for (String key : archived.stringPropertyNames().stream().sorted().collect(java.util.stream.Collectors.toList())) {
            content.append(key).append('=').append(archived.getProperty(key)).append('\n');
        }
        return sha256(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Hashes the canonical serialization of a history, which is the exact input handed to
     * every checker. Serializing to a temporary file keeps this independent of serializers
     * that only accept a path.
     */
    public static String ofHistory(History history, String historyType) {
        Path temp = null;
        try {
            temp = Files.createTempFile("isovista-fingerprint-", ".hist");
            HistorySerializerFactory.getHistorySerializer(historyType).serializeHistory(history, temp.toString());
            return sha256(temp);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot fingerprint history", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of the temporary serialization
                }
            }
        }
    }
}
