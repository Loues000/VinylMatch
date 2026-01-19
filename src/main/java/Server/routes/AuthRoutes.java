package Server.routes;

import Server.auth.SpotifyOAuthService;
import Server.cache.PlaylistCache;
import Server.http.HttpUtils;
import Server.session.CookieUtils;
import Server.session.SpotifySession;
import Server.session.SpotifySessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles Spotify authentication routes with session-based multi-user support.
 */
public class AuthRoutes {

    private final PlaylistCache playlistCache;
    private final SpotifySessionStore sessionStore;
    private final SpotifyOAuthService oauthService;

    public AuthRoutes(PlaylistCache playlistCache, SpotifySessionStore sessionStore, SpotifyOAuthService oauthService) {
        this.playlistCache = playlistCache;
        this.sessionStore = sessionStore;
        this.oauthService = oauthService;
    }

    public void register(HttpServer server) {
        server.createContext("/api/auth/status", this::handleStatus);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/auth/logout", this::handleLogout);
        server.createContext("/api/auth/callback", this::handleCallback);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            // Check session-based login first
            SpotifySession session = sessionStore.getSession(exchange);
            boolean loggedIn = session != null && session.isLoggedIn();

            // Fall back to legacy global token check
            if (!loggedIn) {
                loggedIn = com.hctamlyniv.SpotifyAuth.isUserLoggedIn();
            }

            HttpUtils.sendJson(exchange, 200, Map.of("loggedIn", loggedIn));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler bei Auth-Status: " + e.getMessage());
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            if (!oauthService.isConfigured()) {
                HttpUtils.sendError(exchange, 503, "Spotify credentials not configured");
                return;
            }

            // Create a session for this login attempt
            SpotifySession session = sessionStore.getOrCreateSession(exchange);
            
            // Build authorization URL with CSRF state
            String url = oauthService.buildAuthorizationUrl(session.getSessionId());
            
            HttpUtils.sendJson(exchange, 200, Map.of("authorizeUrl", url));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Start des Logins: " + e.getMessage());
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
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
                session = sessionStore.createSession(exchange);
            }

            // Exchange code for tokens
            boolean success = oauthService.exchangeCodeForTokens(code, state, session);
            
            if (success) {
                // Also update the legacy global tokens for backwards compatibility
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
            sendCallbackHtml(exchange, false, "Error: " + e.getMessage());
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            // Destroy session
            sessionStore.destroySession(exchange);
            
            // Also logout from legacy global store
            com.hctamlyniv.SpotifyAuth.logout();
            
            playlistCache.invalidateForAuthChange(null);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Logout: " + e.getMessage());
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

        // Fall back to legacy global token
        if (com.hctamlyniv.SpotifyAuth.isUserLoggedIn()) {
            try { com.hctamlyniv.SpotifyAuth.refreshAccessToken(); } catch (Exception ignored) {}
            return com.hctamlyniv.SpotifyAuth.getAccessToken();
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
        
        // Legacy fallback
        String refreshToken = com.hctamlyniv.SpotifyAuth.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            return refreshToken;
        }
        String accessToken = com.hctamlyniv.SpotifyAuth.getAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            return accessToken;
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
        return com.hctamlyniv.SpotifyAuth.isUserLoggedIn();
    }

    private void sendCallbackHtml(HttpExchange exchange, boolean success, String message) throws IOException {
        String status = success ? "Login erfolgreich" : "Login fehlgeschlagen";
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
                    <p class="close-hint">Du kannst dieses Fenster jetzt schließen.</p>
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
