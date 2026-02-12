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
        server.createContext("/api/auth/status", this::handleStatus).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/login", this::handleLogin).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/logout", this::handleLogout).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/callback", this::handleCallback).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
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
            
            String userId = null;
            boolean isAdmin = false;
            
            if (loggedIn && session != null) {
                userId = session.getUserId();
                if (userId != null && !userId.isBlank()) {
                    isAdmin = com.hctamlyniv.Config.getAdminUserIds().contains(userId);
                }
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("loggedIn", loggedIn);
            if (userId != null && !userId.isBlank()) {
                response.put("userId", userId);
                response.put("isAdmin", isAdmin);
            }

            HttpUtils.sendJson(exchange, 200, response);
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
            String receivedState = state;
            if (session == null) {
                String host = exchange.getRequestHeaders().getFirst("Host");
                log.warn("No session cookie on Spotify callback (Host={}). This is usually a hostname mismatch (localhost vs 127.0.0.1) between /api/auth/login and the redirect URI. Ensure you use the same hostname (prefer 127.0.0.1) for both login and callback.", host);
                session = sessionStore.createSession(exchange);
                log.info("Created new session for callback: {}", session.getSessionId());
            } else {
                log.info("Found existing session for callback: {}", session.getSessionId());
            }

            log.info("Processing callback with code length: {}, state: {}", code != null ? code.length() : 0, receivedState);
            
            // Exchange code for tokens
            boolean success = oauthService.exchangeCodeForTokens(code, receivedState, session);
            
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
     * Resolves the best available token for playlist loading.
     * Prefers a user session token and falls back to app-level client credentials.
     */
    public AccessTokenResolution resolvePlaylistAccessToken(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            if (session.isTokenExpired()) {
                oauthService.refreshAccessToken(session);
            }
            String token = session.getAccessToken();
            if (token != null && !token.isBlank()) {
                return new AccessTokenResolution(token, true);
            }
        }

        String appToken = oauthService.getClientCredentialsAccessToken().orElse(null);
        if (appToken != null && !appToken.isBlank()) {
            return new AccessTokenResolution(appToken, false);
        }
        return new AccessTokenResolution(null, false);
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
        String status = success ? "Spotify connected" : "Spotify connection failed";
        String badge = success ? "SUCCESS" : "ERROR";
        String closeHint = success ? "This window closes automatically in a moment." : "You can close this window and try again.";
        String safeMessage = escapeHtml(message);
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>VinylMatch - %s</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {
                        margin: 0;
                        min-height: 100vh;
                        display: grid;
                        place-items: center;
                        padding: 20px;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
                        background: #f3f3f3;
                        color: #312f2c;
                    }
                    .card {
                        width: min(460px, 100%%);
                        border-radius: 16px;
                        border: 1px solid rgba(49, 47, 44, 0.14);
                        background: #ffffff;
                        box-shadow: 0 10px 30px rgba(49, 47, 44, 0.08);
                        padding: 26px 24px;
                        text-align: center;
                    }
                    .eyebrow {
                        margin: 0 0 10px;
                        font-size: 11px;
                        letter-spacing: 0.16em;
                        font-weight: 700;
                        text-transform: uppercase;
                        color: rgba(49, 47, 44, 0.62);
                    }
                    .badge {
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        min-width: 86px;
                        height: 28px;
                        border-radius: 999px;
                        font-size: 11px;
                        font-weight: 700;
                        letter-spacing: 0.06em;
                        margin-bottom: 16px;
                    }
                    .badge-success {
                        background: rgba(31, 122, 63, 0.12);
                        color: #1f7a3f;
                        border: 1px solid rgba(31, 122, 63, 0.2);
                    }
                    .badge-error {
                        background: rgba(178, 51, 51, 0.12);
                        color: #b23333;
                        border: 1px solid rgba(178, 51, 51, 0.2);
                    }
                    h1 {
                        margin: 0 0 12px;
                        font-size: clamp(22px, 4vw, 28px);
                        line-height: 1.2;
                        letter-spacing: -0.01em;
                    }
                    p {
                        margin: 0;
                        color: rgba(49, 47, 44, 0.82);
                        line-height: 1.6;
                    }
                    .close-hint {
                        margin-top: 12px;
                        color: rgba(49, 47, 44, 0.62);
                    }
                    .actions {
                        margin-top: 22px;
                    }
                    .btn {
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        min-width: 170px;
                        height: 40px;
                        border-radius: 10px;
                        border: 1px solid rgba(49, 47, 44, 0.18);
                        background: #312f2c;
                        color: #fafafa;
                        text-decoration: none;
                        font-weight: 650;
                        font-size: 14px;
                        cursor: pointer;
                    }
                    .btn:hover {
                        background: #262422;
                    }
                    @media (prefers-color-scheme: dark) {
                        body {
                            background: #312f2c;
                            color: #fafafa;
                        }
                        .card {
                            background: rgba(250, 250, 250, 0.06);
                            border-color: rgba(250, 250, 250, 0.16);
                            box-shadow: none;
                        }
                        .eyebrow {
                            color: rgba(250, 250, 250, 0.62);
                        }
                        p {
                            color: rgba(250, 250, 250, 0.82);
                        }
                        .close-hint {
                            color: rgba(250, 250, 250, 0.62);
                        }
                        .badge-success {
                            background: rgba(121, 219, 161, 0.14);
                            color: #79dba1;
                            border-color: rgba(121, 219, 161, 0.26);
                        }
                        .badge-error {
                            background: rgba(255, 151, 151, 0.16);
                            color: #ff9797;
                            border-color: rgba(255, 151, 151, 0.26);
                        }
                        .btn {
                            background: #fafafa;
                            color: #312f2c;
                            border-color: rgba(250, 250, 250, 0.2);
                        }
                        .btn:hover {
                            background: #efefef;
                        }
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <p class="eyebrow">VinylMatch</p>
                    <div class="badge %s">%s</div>
                    <h1>%s</h1>
                    <p>%s</p>
                    <p class="close-hint">%s</p>
                    <div class="actions">
                        <a class="btn" id="oauth-callback-action" href="/">Back to app</a>
                    </div>
                </div>
                <script>
                    (function() {
                        var success = %s;
                        var payload = { type: 'spotify-auth-callback', success: success };
                        if (window.opener && !window.opener.closed) {
                            try {
                                window.opener.postMessage(payload, window.location.origin);
                            } catch (e) {
                                window.opener.postMessage(payload, '*');
                            }
                        }

                        var action = document.getElementById('oauth-callback-action');
                        if (action) {
                            action.addEventListener('click', function(event) {
                                if (window.opener) {
                                    event.preventDefault();
                                    window.close();
                                    if (!window.closed) {
                                        window.location.href = '/';
                                    }
                                }
                            });
                        }

                        if (success) {
                            setTimeout(function() {
                                window.close();
                                if (!window.closed) {
                                    window.location.href = '/';
                                }
                            }, 1200);
                        }
                    }
                    )();
                </script>
            </body>
            </html>
            """.formatted(
                status,
                success ? "badge-success" : "badge-error",
                badge,
                status,
                safeMessage,
                closeHint,
                success
            );

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record AccessTokenResolution(String token, boolean userAuthenticated) {}
}
