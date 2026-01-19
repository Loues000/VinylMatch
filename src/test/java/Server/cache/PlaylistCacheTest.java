package Server.cache;

import Server.PlaylistData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PlaylistCache.
 */
class PlaylistCacheTest {

    private PlaylistCache cache;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        cache = new PlaylistCache(mapper);
    }

    @Test
    @DisplayName("lookup returns null for non-existent key")
    void lookupReturnsNullForNonExistentKey() {
        PlaylistCacheKey key = new PlaylistCacheKey("non-existent", "user123", 0, 20);
        PlaylistData result = cache.lookup(key);
        assertNull(result, "Should return null for non-existent key");
    }

    @Test
    @DisplayName("store and lookup returns same data")
    void storeAndLookupReturnsSameData() {
        PlaylistCacheKey key = new PlaylistCacheKey("test-playlist", "user123", 0, 20);
        PlaylistData data = new PlaylistData(
            "Test Playlist",
            "https://example.com/cover.jpg",
            "https://open.spotify.com/playlist/test",
            List.of(),
            0,
            0,
            0,
            false
        );

        cache.store(key, data);
        PlaylistData result = cache.lookup(key);

        assertNotNull(result, "Should return stored data");
        assertEquals("Test Playlist", result.getPlaylistName(), "Playlist name should match");
    }

    @Test
    @DisplayName("remove clears cached entry")
    void removesClearsEntry() {
        PlaylistCacheKey key = new PlaylistCacheKey("remove-test", "user123", 0, 20);
        PlaylistData data = new PlaylistData(
            "Remove Test",
            null,
            null,
            List.of(),
            0,
            0,
            0,
            false
        );

        cache.store(key, data);
        assertNotNull(cache.lookup(key), "Should have data before remove");

        cache.remove(key);
        assertNull(cache.lookup(key), "Should be null after remove");
    }

    @Test
    @DisplayName("invalidateForAuthChange clears all entries")
    void invalidateForAuthChangeClearsAllEntries() {
        PlaylistCacheKey key1 = new PlaylistCacheKey("playlist1", "user1", 0, 20);
        PlaylistCacheKey key2 = new PlaylistCacheKey("playlist2", "user1", 0, 20);
        PlaylistData data = new PlaylistData("Test", null, null, List.of(), 0, 0, 0, false);

        cache.store(key1, data);
        cache.store(key2, data);

        // Simulate auth change
        cache.invalidateForAuthChange("new-user-signature");

        assertNull(cache.lookup(key1), "Should be cleared after auth change");
        assertNull(cache.lookup(key2), "Should be cleared after auth change");
    }

    @Test
    @DisplayName("same auth signature does not clear cache")
    void sameAuthSignatureDoesNotClearCache() {
        PlaylistCacheKey key = new PlaylistCacheKey("same-auth-test", "user1", 0, 20);
        PlaylistData data = new PlaylistData("Test", null, null, List.of(), 0, 0, 0, false);

        cache.invalidateForAuthChange("same-signature");
        cache.store(key, data);

        // Same signature should not clear
        cache.invalidateForAuthChange("same-signature");

        assertNotNull(cache.lookup(key), "Should still have data with same auth signature");
    }
}
