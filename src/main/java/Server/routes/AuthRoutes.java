package Server.routes;

import Server.auth.SpotifyOAuthService;
import Server.cache.PlaylistCache;
import Server.http.ApiFilters;
import Server.http.HttpUtils;
import Server.session.SpotifySession;
import Server.session.SpotifySessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles Spotify authentication routes with session-based multi-user support.
 */
public class AuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private final PlaylistCache playlistCache;
    private final SpotifySessionStore sessionStore;
    private final SpotifyOAuthService oauthService;

    public AuthRoutes(PlaylistCache playlistCache, SpotifySessionStore sessionStore, SpotifyOAuthService oauthService) {
        this.playlistCache = playlistCache;
        this.sessionStore = sessionStore;
        this.oauthService = oauthService;
    }

    public void register(HttpServer server) {
        server.createContext("/api/auth/status", this::handleStatus).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/auth/login", this::handleLogin).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/auth/logout", this::handleLogout).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/auth/callback", this::handleCallback).getFilters().add(ApiFilters.rateLimiting());
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            // Check session-based login first
            SpotifySession session = sessionStore.getSession(exchange);
            boolean loggedIn = session != null && session.isLoggedIn();

            HttpUtils.sendJson(exchange, 200, Map.of("loggedIn", loggedIn));
        } catch (Exception e) {
            log.warn("Auth status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "auth_status_failed", "Failed to read auth status");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            if (!oauthService.isConfigured()) {
                HttpUtils.sendApiError(exchange, 503, "spotify_not_configured", "Spotify OAuth is not configured");
                return;
            }

            // Create a session for this login attempt
            SpotifySession session = sessionStore.getOrCreateSession(exchange);
            
            // Build authorization URL with CSRF state
            URI redirectOverride = oauthService.isRedirectUriExplicit() ? null : deriveLoopbackRedirectUri(exchange);
            String url = oauthService.buildAuthorizationUrl(session.getSessionId(), redirectOverride);
            
            HttpUtils.sendJson(exchange, 200, Map.of("authorizeUrl", url));
        } catch (Exception e) {
            log.warn("Auth login start failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "auth_login_failed", "Failed to start login");
        }
    }

    /**
     * For local development, Spotify requires an explicit loopback IP literal (e.g. 127.0.0.1) and does not allow
     * localhost as a redirect URI. We still derive the port from the current request so dynamic ports work.
     *
     * This intentionally only trusts loopback hosts to avoid Host-header based redirect manipulation.
     */
    private static URI deriveLoopbackRedirectUri(HttpExchange exchange) {
        if (exchange == null) return null;
        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
        if (hostHeader == null || hostHeader.isBlank()) return null;

        String scheme = HttpUtils.isSecureRequest(exchange) ? "https" : "http";
        URI base;
        try {
            base = URI.create(scheme + "://" + hostHeader.trim());
        } catch (Exception e) {
            return null;
        }

        String host = base.getHost();
        if (host == null || host.isBlank()) return null;
        String lowerHost = host.toLowerCase();
        boolean isLoopback = lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1") || lowerHost.equals("::1");
        if (!isLoopback) return null;

        int port = base.getPort();
        if (port <= 0) {
            try {
                port = exchange.getLocalAddress().getPort();
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            String canonicalHost = lowerHost.equals("::1") ? "::1" : "127.0.0.1";
            return new URI(scheme, null, canonicalHost, port, "/api/auth/callback", null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendText(exchange, 405, "Only GET is supported");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            String code = params.get("code");
            String state = params.get("state");
            String error = params.get("error");

            // Handle OAuth errors
            if (error != null && !error.isBlank()) {
                sendCallbackHtml(exchange, false, "Authorization denied: " + error);
                return;
            }

            if (code == null || code.isBlank()) {
                sendCallbackHtml(exchange, false, "Missing authorization code");
                return;
            }

            if (state == null || state.isBlank()) {
                sendCallbackHtml(exchange, false, "Missing state parameter (CSRF protection)");
                return;
            }

            // Get or create session
            SpotifySession session = sessionStore.getSession(exchange);
            if (session == null) {
                String host = exchange.getRequestHeaders().getFirst("Host");
                log.warn("No session cookie on Spotify callback (Host={}). This is usually a hostname mismatch (localhost vs 127.0.0.1) between /api/auth/login and the redirect URI. Ensure you use the same hostname (prefer 127.0.0.1) for both login and callback.", host);
                session = sessionStore.createSession(exchange);
            }

            // Exchange code for tokens
            boolean success = oauthService.exchangeCodeForTokens(code, state, session);
            
            if (success) {
                try {
                    // Store in session store
                    sessionStore.storeSession(session);
                } catch (Exception ignored) {}

                playlistCache.invalidateForAuthChange(session.getSessionId());
                sendCallbackHtml(exchange, true, "Login successful! You can close this window.");
            } else {
                sendCallbackHtml(exchange, false, "Token exchange failed. Please try again.");
            }
        } catch (Exception e) {
            log.warn("Auth callback failed: {}", e.getMessage());
            sendCallbackHtml(exchange, false, "Error: " + e.getMessage());
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            // Destroy session
            sessionStore.destroySession(exchange);

            playlistCache.invalidateForAuthChange(null);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            log.warn("Logout failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "logout_failed", "Failed to logout");
        }
    }

    /**
     * Gets the access token for a request, checking session first then falling back to legacy.
     */
    public String getAccessToken(HttpExchange exchange) {
        // Try session-based token first
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            // Refresh if needed
            if (session.isTokenExpired()) {
                oauthService.refreshAccessToken(session);
            }
            String token = session.getAccessToken();
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        return null;
    }

    /**
     * Gets a user signature for cache keying.
     */
    public String getUserSignature(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null) {
            return session.getSessionId();
        }
        return "";
    }

    /**
     * Checks if the request is authenticated.
     */
    public boolean isAuthenticated(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            return true;
        }
        return false;
    }

    private void sendCallbackHtml(HttpExchange exchange, boolean success, String message) throws IOException {
        String status = success ? "Login successful" : "Login failed";
        String color = success ? "#1DB954" : "#e74c3c";
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>VinylMatch - %s</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        margin: 0;
                        background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%);
                        color: white;
                    }
                    .card {
                        background: rgba(255,255,255,0.1);
                        border-radius: 16px;
                        padding: 40px;
                        text-align: center;
                        backdrop-filter: blur(10px);
                        max-width: 400px;
                    }
                    h1 {
                        color: %s;
                        margin-bottom: 16px;
                    }
                    p {
                        color: rgba(255,255,255,0.8);
                        line-height: 1.6;
                    }
                    .close-hint {
                        margin-top: 24px;
                        font-size: 14px;
                        opacity: 0.6;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>%s</h1>
                    <p>%s</p>
                    <p class="close-hint">You can close this window now.</p>
                </div>
                <script>
                    if (window.opener) {
                        window.opener.postMessage({ type: 'spotify-auth-callback', success: %s }, '*');
                    }
                </script>
            </body>
            </html>
            """.formatted(status, color, status, message, success);

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
