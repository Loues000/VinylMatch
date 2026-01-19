package Server.cache;

/**
 * Cache key for playlist data, combining playlist ID with user context.
 */
public record PlaylistCacheKey(
    String playlistId,
    String userSignature,
    int offset,
    int limit
) {}
