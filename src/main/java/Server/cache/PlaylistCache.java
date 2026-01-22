package Server.cache;

import Server.PlaylistData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-level cache for playlist data: in-memory with TTL + disk snapshots.
 */
public class PlaylistCache {

    private static final Logger log = LoggerFactory.getLogger(PlaylistCache.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Path CACHE_DIR = Paths.get("cache", "playlists");
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<PlaylistCacheKey, PlaylistCacheEntry> memoryCache = new ConcurrentHashMap<>();
    private volatile String lastAuthSignature = null;

    public PlaylistCache(ObjectMapper mapper) {
        this.mapper = mapper;
        ensureCacheDir();
    }

    private void ensureCacheDir() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            log.warn("Failed to create cache directory: {}", e.getMessage());
        }
    }

    /**
     * Looks up a playlist from memory or disk cache.
     */
    public PlaylistData lookup(PlaylistCacheKey key) {
        long now = System.currentTimeMillis();

        // Check memory cache
        PlaylistCacheEntry entry = memoryCache.get(key);
        if (entry != null) {
            if (entry.isExpired(now)) {
                memoryCache.remove(key, entry);
                deleteSnapshot(key);
                entry = null;
            } else {
                return entry.playlistData();
            }
        }

        // Check disk cache
        PlaylistCacheEntry snapshotEntry = readSnapshot(key);
        if (snapshotEntry != null) {
            memoryCache.put(key, snapshotEntry);
            return snapshotEntry.playlistData();
        }

        return null;
    }

    /**
     * Stores playlist data in both memory and disk cache.
     */
    public void store(PlaylistCacheKey key, PlaylistData playlistData) {
        long expiresAt = System.currentTimeMillis() + CACHE_TTL.toMillis();
        PlaylistCacheEntry entry = new PlaylistCacheEntry(playlistData, expiresAt);
        memoryCache.put(key, entry);
        writeSnapshot(key, entry);
    }

    /**
     * Removes a specific entry from both caches.
     */
    public void remove(PlaylistCacheKey key) {
        memoryCache.remove(key);
        deleteSnapshot(key);
    }

    /**
     * Invalidates all cached data when authentication changes.
     */
    public void invalidateForAuthChange(String newSignature) {
        String normalized = (newSignature == null) ? "" : newSignature;
        String previous = lastAuthSignature;
        if (!Objects.equals(previous, normalized)) {
            memoryCache.clear();
            purgeAllSnapshots();
            lastAuthSignature = normalized;
        }
    }

    // =========================================================================
    // Disk Snapshot Operations
    // =========================================================================

    private PlaylistCacheEntry readSnapshot(PlaylistCacheKey key) {
        Path path = snapshotPath(key);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            PlaylistCacheSnapshot snapshot = mapper.readValue(bytes, PlaylistCacheSnapshot.class);
            long now = System.currentTimeMillis();
            if (snapshot.expiresAtMillis() <= now) {
                Files.deleteIfExists(path);
                return null;
            }
            return new PlaylistCacheEntry(snapshot.playlistData(), snapshot.expiresAtMillis());
        } catch (IOException e) {
            log.warn("Failed to read cache snapshot: {}", e.getMessage());
            return null;
        }
    }

    private void writeSnapshot(PlaylistCacheKey key, PlaylistCacheEntry entry) {
        try {
            Files.createDirectories(CACHE_DIR);
            PlaylistCacheSnapshot snapshot = new PlaylistCacheSnapshot(entry.playlistData(), entry.expiresAtMillis());
            byte[] bytes = mapper.writeValueAsBytes(snapshot);
            Files.write(snapshotPath(key), bytes);
        } catch (IOException e) {
            log.warn("Failed to write cache snapshot: {}", e.getMessage());
        }
    }

    private void deleteSnapshot(PlaylistCacheKey key) {
        try {
            Files.deleteIfExists(snapshotPath(key));
        } catch (IOException e) {
            log.warn("Failed to delete cache snapshot: {}", e.getMessage());
        }
    }

    private void purgeAllSnapshots() {
        try {
            if (!Files.exists(CACHE_DIR)) {
                return;
            }
            try (var stream = Files.list(CACHE_DIR)) {
                stream.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {}
                });
            }
        } catch (IOException e) {
            log.warn("Failed to purge cache directory: {}", e.getMessage());
        }
    }

    private Path snapshotPath(PlaylistCacheKey key) {
        return CACHE_DIR.resolve(hashCacheKey(key) + ".json");
    }

    private String hashCacheKey(PlaylistCacheKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((key.playlistId() == null ? "" : key.playlistId()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update((key.userSignature() == null ? "" : key.userSignature()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.offset()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.limit()).getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
