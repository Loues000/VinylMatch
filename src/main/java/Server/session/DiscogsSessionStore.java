package Server.session;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for Discogs sessions.
 */
public class DiscogsSessionStore {

    private static final String COOKIE_NAME = "discogs_session";
    private static final int COOKIE_MAX_AGE = 86400; // 24 hours

    private final Map<String, DiscogsSession> sessions = new ConcurrentHashMap<>();

    /**
     * Retrieves the Discogs session for the current request, if any.
     */
    public DiscogsSession getSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * Creates a new session and sets the session cookie.
     */
    public DiscogsSession createSession(HttpExchange exchange, String token, String userAgent, String username, String displayName) {
        // Remove any existing session for this user
        String oldSessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (oldSessionId != null) {
            sessions.remove(oldSessionId);
        }
        sessions.entrySet().removeIf(entry -> username.equals(entry.getValue().username()));

        // Create new session
        String sessionId = UUID.randomUUID().toString();
        DiscogsSession session = new DiscogsSession(sessionId, token, userAgent, username, displayName);
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
}
