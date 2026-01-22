package com.hctamlyniv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises Config's file parsing and fallback behavior without relying on process env mutation.
 */
class ConfigInternalTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() throws Exception {
        resetConfig();
    }

    @Test
    void loadsDotEnvAndStripsQuotes() throws Exception {
        resetConfig();

        Path envFile = tempDir.resolve(".env");
        Files.writeString(
                envFile,
                """
                        # comment
                        SPOTIFY_CLIENT_ID="my-id"
                        SPOTIFY_CLIENT_SECRET='my-secret'
                        DISCOGS_TOKEN=token
                        PORT=9999
                        UNKNOWN_KEY=ignored
                        """,
                StandardCharsets.UTF_8
        );

        invokePrivate("loadFromDotEnvIfPresent", new Class<?>[]{Path.class}, new Object[]{envFile});
        setInitialized(true);

        assertEquals("my-id", Config.getSpotifyClientId());
        assertEquals("my-secret", Config.getSpotifyClientSecret());
        assertEquals("token", Config.getDiscogsToken());
        assertEquals(9999, Config.getPort());
    }

    @Test
    void loadsPropertiesFileWhenPresent() throws Exception {
        resetConfig();

        Path props = tempDir.resolve("config.properties");
        Files.writeString(
                props,
                """
                        SPOTIFY_CLIENT_ID=from-props
                        SPOTIFY_CLIENT_SECRET=from-props-secret
                        CORS_ALLOWED_ORIGINS=http://localhost:3000
                        """,
                StandardCharsets.UTF_8
        );

        invokePrivate("loadFromPropertiesIfPresent", new Class<?>[]{Path.class}, new Object[]{props});
        setInitialized(true);

        assertEquals("from-props", Config.getSpotifyClientId());
        assertEquals("from-props-secret", Config.getSpotifyClientSecret());
        assertEquals("http://localhost:3000", Config.getCorsAllowedOrigins());
    }

    @Test
    void portFallsBackToDefaultOnInvalidValue() throws Exception {
        resetConfig();
        Map<String, String> cache = getStatic("cache");
        cache.put("PORT", "not-a-number");
        setInitialized(true);

        assertEquals(8888, Config.getPort());
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(String fieldName) throws Exception {
        Field f = Config.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static void setInitialized(boolean value) throws Exception {
        Field f = Config.class.getDeclaredField("initialized");
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    private static void resetConfig() throws Exception {
        Map<String, String> cache = getStatic("cache");
        cache.clear();
        List<String> sources = getStatic("loadedSources");
        sources.clear();
        setInitialized(false);
    }

    private static Object invokePrivate(String name, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = Config.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
