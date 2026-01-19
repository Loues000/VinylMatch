package Server.session;

/**
 * Represents an authenticated Spotify session with tokens.
 */
public class SpotifySession {

    private final String sessionId;
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile String userId;
    private volatile long tokenExpiresAt;

    public SpotifySession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(long tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public boolean isLoggedIn() {
        return (refreshToken != null && !refreshToken.isBlank()) ||
               (accessToken != null && !accessToken.isBlank());
    }

    public boolean isTokenExpired() {
        return System.currentTimeMillis() >= tokenExpiresAt;
    }
}
