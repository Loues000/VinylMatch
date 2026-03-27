package Server.session;

import com.hctamlyniv.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEncryptionTest {

    @AfterEach
    void cleanup() throws Exception {
        resetConfig();
    }

    @Test
    void configuredMasterKeyDecryptsAcrossInstances() throws Exception {
        resetConfig();
        Map<String, String> cache = getStatic("cache");
        cache.put("VINYLMATCH_MASTER_KEY", "configured-master-key");
        setInitialized(true);

        TokenEncryption first = new TokenEncryption();
        Thread.sleep(5L);
        TokenEncryption second = new TokenEncryption();

        String encrypted = first.encrypt("discogs-secret");
        assertEquals("discogs-secret", second.decrypt(encrypted));
    }

    @Test
    void fallbackMasterKeyRemainsStableAcrossInstances() throws Exception {
        resetConfig();

        TokenEncryption first = new TokenEncryption();
        Thread.sleep(5L);
        TokenEncryption second = new TokenEncryption();

        String encrypted = first.encrypt("discogs-secret");
        assertEquals("discogs-secret", second.decrypt(encrypted));
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(String fieldName) throws Exception {
        Field field = Config.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static void setInitialized(boolean value) throws Exception {
        Field field = Config.class.getDeclaredField("initialized");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static void resetConfig() throws Exception {
        Map<String, String> cache = getStatic("cache");
        cache.clear();
        List<String> sources = getStatic("loadedSources");
        sources.clear();
        setInitialized(false);
        System.clearProperty("vinylmatch.master.key");
    }
}
