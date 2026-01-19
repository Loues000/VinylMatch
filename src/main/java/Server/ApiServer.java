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

import java.io.IOException;
import java.net.InetSocketAddress;
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

    private static final Map<String, DiscogsService> DISCOGS_SERVICES = new ConcurrentHashMap<>();

    public static void start() throws IOException {
        int port = Config.getPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Initialize shared components
        PlaylistCache playlistCache = new PlaylistCache(HttpUtils.getMapper());
        SpotifySessionStore spotifySessionStore = new SpotifySessionStore();
        DiscogsSessionStore discogsSessionStore = new DiscogsSessionStore();
        SpotifyOAuthService spotifyOAuthService = new SpotifyOAuthService();

        // Register API routes
        AuthRoutes authRoutes = new AuthRoutes(playlistCache, spotifySessionStore, spotifyOAuthService);
        authRoutes.register(server);

        PlaylistRoutes playlistRoutes = new PlaylistRoutes(port, playlistCache, ApiServer::getDefaultDiscogsService, authRoutes);
        playlistRoutes.register(server);

        DiscogsRoutes discogsRoutes = new DiscogsRoutes(ApiServer::getDefaultDiscogsService, discogsSessionStore);
        discogsRoutes.register(server);

        ConfigRoutes configRoutes = new ConfigRoutes();
        configRoutes.register(server);

        // Serve static frontend files
        Path frontendBase = resolveFrontendBase();
        StaticFileHandler staticHandler = new StaticFileHandler(frontendBase, "home.html", port);
        server.createContext("/", staticHandler);

        // Start server
        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();

        System.out.println("Frontend läuft auf http://localhost:" + port + "/");
        System.out.println("Spotify redirect URI: " + spotifyOAuthService.getRedirectUri());
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
        String token = com.hctamlyniv.Config.getDiscogsToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        String userAgent = com.hctamlyniv.Config.getDiscogsUserAgent();
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "VinylMatch/1.0";
        }
        final String tokenFinal = token;
        final String userAgentFinal = userAgent;
        final String key = tokenFinal + "|" + userAgentFinal;
        return DISCOGS_SERVICES.computeIfAbsent(key, k -> new DiscogsService(tokenFinal, userAgentFinal));
    }
}
