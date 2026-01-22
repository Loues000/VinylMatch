package Server;

import Server.auth.SpotifyOAuthService;
import Server.cache.PlaylistCache;
import Server.http.HttpUtils;
import Server.http.StaticFileHandler;
import Server.routes.AuthRoutes;
import Server.routes.ConfigRoutes;
import Server.routes.DiscogsRoutes;
import Server.routes.PlaylistRoutes;
import Server.session.DiscogsSessionStore;
import Server.session.SpotifySessionStore;
import com.hctamlyniv.Config;
import com.hctamlyniv.DiscogsService;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Main HTTP server that wires together all routes and middleware.
 */
public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private static final Map<String, DiscogsService> DISCOGS_SERVICES = new ConcurrentHashMap<>();

    public static HttpServer start() throws IOException {
        return start(Config.getPort());
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        int actualPort = server.getAddress().getPort();

        // Initialize shared components
        PlaylistCache playlistCache = new PlaylistCache(HttpUtils.getMapper());
        SpotifySessionStore spotifySessionStore = new SpotifySessionStore();
        DiscogsSessionStore discogsSessionStore = new DiscogsSessionStore();
        SpotifyOAuthService spotifyOAuthService = new SpotifyOAuthService();

        // Register API routes
        AuthRoutes authRoutes = new AuthRoutes(playlistCache, spotifySessionStore, spotifyOAuthService);
        authRoutes.register(server);

        PlaylistRoutes playlistRoutes = new PlaylistRoutes(playlistCache, ApiServer::getDefaultDiscogsService, authRoutes);
        playlistRoutes.register(server);

        DiscogsRoutes discogsRoutes = new DiscogsRoutes(ApiServer::getDefaultDiscogsService, discogsSessionStore);
        discogsRoutes.register(server);

        ConfigRoutes configRoutes = new ConfigRoutes();
        configRoutes.register(server);

        // Serve static frontend files
        Path frontendBase = resolveFrontendBase();
        StaticFileHandler staticHandler = new StaticFileHandler(frontendBase, "home.html", actualPort, spotifyOAuthService.getRedirectUri());
        server.createContext("/", staticHandler);

        // Start server
        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();

        URI redirectUri = spotifyOAuthService.getRedirectUri();
        String host = (redirectUri != null && redirectUri.getHost() != null) ? redirectUri.getHost() : "127.0.0.1";
        log.info("Server running on http://{}:{}/", host, actualPort);
        log.info("Spotify redirect URI: {}", redirectUri);
        return server;
    }

    private static Path resolveFrontendBase() {
        // Prefer project source tree for dev
        Path src = Paths.get("src", "main", "frontend");
        if (Files.isDirectory(src)) return src;

        // Maven copies frontend to target/frontend during build
        Path target = Paths.get("target", "frontend");
        if (Files.isDirectory(target)) return target;

        // Docker image copies frontend into /app/src/main/frontend, but fall back to current dir layout if needed
        Path fallback = Paths.get("frontend");
        if (Files.isDirectory(fallback)) return fallback;

        // Last resort: keep old behavior (will 404 if missing)
        return src;
    }

    /**
     * Returns the default DiscogsService using environment configuration.
     */
    private static DiscogsService getDefaultDiscogsService() {
        String tokenRaw = com.hctamlyniv.Config.getDiscogsToken();
        String token = (tokenRaw == null || tokenRaw.isBlank()) ? null : tokenRaw.trim();
        String userAgent = com.hctamlyniv.Config.getDiscogsUserAgent();
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "VinylMatch/1.0";
        }
        final String tokenFinal = token;
        final String userAgentFinal = userAgent;
        final String key = (tokenFinal == null ? "" : tokenFinal) + "|" + userAgentFinal;
        return DISCOGS_SERVICES.computeIfAbsent(key, k -> new DiscogsService(tokenFinal, userAgentFinal));
    }
}
