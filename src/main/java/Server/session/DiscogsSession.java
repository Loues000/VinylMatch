package Server.session;

/**
 * Represents an authenticated Discogs session.
 */
public record DiscogsSession(
    String sessionId,
    String token,
    String userAgent,
    String username,
    String displayName
) {}
