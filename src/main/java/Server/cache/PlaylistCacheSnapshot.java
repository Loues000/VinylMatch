package Server.cache;

import Server.PlaylistData;

/**
 * Snapshot structure for disk persistence.
 */
public record PlaylistCacheSnapshot(
    PlaylistData playlistData,
    long expiresAtMillis
) {}
