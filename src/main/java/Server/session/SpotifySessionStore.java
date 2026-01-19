package Server.session;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for Spotify sessions, supporting multi-user hosting.
 */
public class SpotifySessionStore {

    private static final String COOKIE_NAME = "spotify_session";
    private static final int COOKIE_MAX_AGE = 86400 * 30; // 30 days

    private final Map<String, SpotifySession> sessions = new ConcurrentHashMap<>();

    /**
     * Retrieves the Spotify session for the current request, if any.
     */
    public SpotifySession getSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * Gets or creates a session for the request.
     */
    public SpotifySession getOrCreateSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId != null && !sessionId.isBlank()) {
            SpotifySession existing = sessions.get(sessionId);
            if (existing != null) {
                return existing;
            }
        }
        return createSession(exchange);
    }

    /**
     * Creates a new session and sets the session cookie.
     */
    public SpotifySession createSession(HttpExchange exchange) {
        // Remove any existing session
        String oldSessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (oldSessionId != null) {
            sessions.remove(oldSessionId);
        }

        // Create new session
        String sessionId = UUID.randomUUID().toString();
        SpotifySession session = new SpotifySession(sessionId);
        sessions.put(sessionId, session);

        CookieUtils.setCookie(exchange, COOKIE_NAME, sessionId, COOKIE_MAX_AGE, true);
        return session;
    }

    /**
     * Destroys the current session.
     */
    public void destroySession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        CookieUtils.clearCookie(exchange, COOKIE_NAME);
    }

    /**
     * Gets a session by its ID (used for OAuth callback).
     */
    public SpotifySession getSessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * Stores a session directly (used during OAuth callback).
     */
    public void storeSession(SpotifySession session) {
        if (session != null && session.getSessionId() != null) {
            sessions.put(session.getSessionId(), session);
        }
    }
}
