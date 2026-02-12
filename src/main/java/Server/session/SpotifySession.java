package Server.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an authenticated Spotify session with tokens.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpotifySession {

    private final String sessionId;
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile String userId;
    private volatile long tokenExpiresAt;
    private volatile Boolean loggedIn; // For backward compatibility with old Redis data

    @JsonCreator
    public SpotifySession(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken,
            @JsonProperty("userId") String userId,
            @JsonProperty("tokenExpiresAt") long tokenExpiresAt) {
        this.sessionId = sessionId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public SpotifySession(String sessionId) {
        this.sessionId = sessionId;
        this.accessToken = null;
        this.refreshToken = null;
        this.userId = null;
        this.tokenExpiresAt = 0;
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

    public void setLoggedIn(Boolean loggedIn) {
        // This setter exists for Jackson deserialization compatibility
        // The value is computed dynamically in isLoggedIn()
        this.loggedIn = loggedIn;
    }

    public boolean isTokenExpired() {
        return System.currentTimeMillis() >= tokenExpiresAt;
    }
}
