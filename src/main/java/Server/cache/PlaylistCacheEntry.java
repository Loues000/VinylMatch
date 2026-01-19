package Server.cache;

import Server.PlaylistData;

/**
 * Cache entry holding playlist data with expiration.
 */
public record PlaylistCacheEntry(
    PlaylistData playlistData,
    long expiresAtMillis
) {
    public boolean isExpired(long now) {
        return now >= expiresAtMillis;
    }
}
