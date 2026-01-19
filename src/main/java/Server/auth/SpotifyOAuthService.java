package Server.auth;

import Server.session.SpotifySession;
import com.hctamlyniv.Config;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Spotify OAuth flow for hosted multi-user deployment.
 */
public class SpotifyOAuthService {

    private static final String[] SCOPES = {"playlist-read-private", "playlist-read-collaborative"};
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    // Pending authorization states (state -> session id)
    private final Map<String, PendingAuth> pendingAuthorizations = new ConcurrentHashMap<>();

    private final String clientId;
    private final String clientSecret;
    private final URI redirectUri;
    private final SpotifyApi spotifyApi;

    public SpotifyOAuthService() {
        this.clientId = trimOrNull(Config.getSpotifyClientId());
        this.clientSecret = trimOrNull(Config.getSpotifyClientSecret());
        this.redirectUri = buildRedirectUri();
        
        if (clientId != null && clientSecret != null) {
            this.spotifyApi = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRedirectUri(redirectUri)
                    .build();
        } else {
            this.spotifyApi = null;
        }
    }

    public boolean isConfigured() {
        return clientId != null && clientSecret != null && spotifyApi != null;
    }

    /**
     * Generates an authorization URL with CSRF state token.
     * 
     * @param sessionId The session ID to associate with this authorization attempt
     * @return The authorization URL to redirect the user to
     */
    public String buildAuthorizationUrl(String sessionId) {
        if (!isConfigured()) {
            throw new IllegalStateException("Spotify credentials not configured");
        }

        // Generate a secure random state token for CSRF protection
        byte[] stateBytes = new byte[24];
        SECURE_RANDOM.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

        // Store pending authorization
        pendingAuthorizations.put(state, new PendingAuth(sessionId, System.currentTimeMillis()));
        
        // Clean up old pending authorizations (older than 10 minutes)
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000);
        pendingAuthorizations.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);

        AuthorizationCodeUriRequest uriRequest = spotifyApi.authorizationCodeUri()
                .scope(String.join(" ", SCOPES))
                .state(state)
                .build();

        return uriRequest.execute().toString();
    }

    /**
     * Exchanges an authorization code for tokens and populates the session.
     * 
     * @param code The authorization code from the callback
     * @param state The state token for CSRF validation
     * @param session The session to populate with tokens
     * @return true if successful
     */
    public boolean exchangeCodeForTokens(String code, String state, SpotifySession session) {
        if (!isConfigured()) {
            return false;
        }

        // Validate state token
        PendingAuth pending = pendingAuthorizations.remove(state);
        if (pending == null) {
            System.err.println("[OAuth] Invalid or expired state token");
            return false;
        }

        // Verify session ID matches
        if (!pending.sessionId().equals(session.getSessionId())) {
            System.err.println("[OAuth] Session ID mismatch");
            return false;
        }

        try {
            AuthorizationCodeCredentials credentials = spotifyApi.authorizationCode(code).build().execute();

            session.setAccessToken(credentials.getAccessToken());
            session.setRefreshToken(credentials.getRefreshToken());
            
            // Set expiration with 30-second buffer
            long expiresInMs = Math.max(0, (credentials.getExpiresIn() - 30)) * 1000L;
            session.setTokenExpiresAt(System.currentTimeMillis() + expiresInMs);

            System.out.println("[OAuth] Tokens obtained successfully");
            return true;
        } catch (Exception e) {
            System.err.println("[OAuth] Token exchange failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes the access token for a session.
     * 
     * @param session The session whose token should be refreshed
     * @return true if successful
     */
    public boolean refreshAccessToken(SpotifySession session) {
        if (!isConfigured() || session == null) {
            return false;
        }

        String refreshToken = session.getRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }

        try {
            SpotifyApi api = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(refreshToken)
                    .build();

            AuthorizationCodeCredentials credentials = api.authorizationCodeRefresh().build().execute();
            
            session.setAccessToken(credentials.getAccessToken());
            
            // Some refreshes return a new refresh token
            String newRefreshToken = credentials.getRefreshToken();
            if (newRefreshToken != null && !newRefreshToken.isBlank()) {
                session.setRefreshToken(newRefreshToken);
            }
            
            long expiresInMs = Math.max(0, (credentials.getExpiresIn() - 30)) * 1000L;
            session.setTokenExpiresAt(System.currentTimeMillis() + expiresInMs);

            return true;
        } catch (Exception e) {
            System.err.println("[OAuth] Token refresh failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensures the session has a valid (non-expired) access token.
     * Refreshes if needed.
     */
    public boolean ensureValidToken(SpotifySession session) {
        if (session == null || !session.isLoggedIn()) {
            return false;
        }

        if (session.isTokenExpired()) {
            return refreshAccessToken(session);
        }

        return true;
    }

    public URI getRedirectUri() {
        return redirectUri;
    }

    private static URI buildRedirectUri() {
        // Check for explicit redirect URI from config/env
        String configuredUri = Config.getSpotifyRedirectUri();
        if (configuredUri != null && !configuredUri.isBlank()) {
            return SpotifyHttpManager.makeUri(configuredUri.trim());
        }

        // Check for public base URL
        String publicBaseUrl = Config.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.trim();
            if (!base.endsWith("/")) {
                base += "/";
            }
            return SpotifyHttpManager.makeUri(base + "api/auth/callback");
        }

        // Default to localhost
        int port = Config.getPort();
        return SpotifyHttpManager.makeUri("http://127.0.0.1:" + port + "/api/auth/callback");
    }

    private static String trimOrNull(String s) {
        return (s == null) ? null : s.trim();
    }

    private record PendingAuth(String sessionId, long createdAt) {}
}
