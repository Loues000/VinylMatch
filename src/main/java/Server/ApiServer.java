package Server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import com.hctamlyniv.ReceivingData;

public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FRONTEND_BASE = Paths.get("src/main/frontend");

    public static void start() throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8888"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // API-Route
        server.createContext("/api/playlist", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur GET erlaubt");
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                String id = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("id=")) {
                            id = param.substring(3);
                            break;
                        }
                    }
                }
                if (id == null || id.isBlank()) {
                    sendError(exchange, 400, "Missing or invalid 'id' query parameter");
                    return;
                }

                System.out.println("[API] Requested: http://localhost:" + port + "/api/playlist?id=" + id);

                // Wähle Token dynamisch: für Playlist-Endpunkte ist ein Benutzer-Token erforderlich
                String token = null;
                if (com.hctamlyniv.SpotifyAuth.isUserLoggedIn()) {
                    // optional refresh; wenn fehlgeschlagen, verbleibt ggf. altes Token
                    try { com.hctamlyniv.SpotifyAuth.refreshAccessToken(); } catch (Exception ignored) {}
                    token = com.hctamlyniv.SpotifyAuth.getAccessToken();
                }
                if (token == null || token.isBlank()) {
                    sendError(exchange, 401, "Spotify-Login erforderlich. Bitte über den Login-Button anmelden.");
                    return;
                }

                ReceivingData rd = new ReceivingData(token, id);
                PlaylistData playlistData = rd.loadPlaylistData();
                if (playlistData == null) {
                    sendError(exchange, 500, "Failed to load playlist data");
                    return;
                }
                String json = MAPPER.writeValueAsString(playlistData);
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Error loading playlist: " + e.getMessage());
            }
        });

        // Discogs-Suche (on-demand)
        server.createContext("/api/discogs/search", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }

                // Request-Body lesen
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                java.util.Map payload = MAPPER.readValue(body, java.util.Map.class);
                Object a = payload.get("artist");
                Object b = payload.get("album");
                Object y = payload.get("releaseYear");
                Object t = payload.get("track");
                String artist = (a instanceof String s && !s.isBlank()) ? s : null;
                String album = (b instanceof String s && !s.isBlank()) ? s : null;
                Integer year = null;
                if (y instanceof Number n) {
                    year = n.intValue();
                } else if (y instanceof String ys) {
                    String ts = ys.trim();
                    if (ts.length() >= 4) {
                        try { year = Integer.parseInt(ts.substring(0, 4)); } catch (Exception ignored) {}
                    }
                }

                String trackTitle = (t instanceof String s && !s.isBlank()) ? s : null;

                if (artist == null || album == null) {
                    sendError(exchange, 400, "Felder 'artist' und 'album' sind erforderlich");
                    return;
                }

                String token = com.hctamlyniv.Config.getDiscogsToken();
                String ua = com.hctamlyniv.Config.getDiscogsUserAgent();
                com.hctamlyniv.DiscogsService discogs = (token != null && !token.isBlank())
                        ? new com.hctamlyniv.DiscogsService(token, (ua == null || ua.isBlank()) ? "VinylMatch/1.0" : ua)
                        : null;
                if (discogs == null) {
                    sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                    return;
                }

                java.util.Optional<String> urlOpt = discogs.findAlbumUri(artist, album, year, trackTitle);
                if (urlOpt.isEmpty()) {
                    sendError(exchange, 404, "Kein Discogs-Treffer gefunden");
                    return;
                }

                String json = MAPPER.writeValueAsString(java.util.Map.of("url", urlOpt.get()));
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler bei Discogs-Suche: " + e.getMessage());
            }
        });

        // Auth-Status
        server.createContext("/api/auth/status", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"GET".equalsIgnoreCase(method)) {
                    sendError(exchange, 405, "Nur GET erlaubt");
                    return;
                }
                boolean loggedIn = com.hctamlyniv.SpotifyAuth.isUserLoggedIn();
                String json = MAPPER.writeValueAsString(java.util.Map.of("loggedIn", loggedIn));
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler bei Auth-Status: " + e.getMessage());
            }
        });

        // Login starten (liefert Authorization-URL)
        server.createContext("/api/auth/login", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                com.hctamlyniv.SpotifyAuth.startCallbackServerIfNeeded();
                String url = com.hctamlyniv.SpotifyAuth.buildAuthorizationUrl();
                String json = MAPPER.writeValueAsString(java.util.Map.of("authorizeUrl", url));
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Start des Logins: " + e.getMessage());
            }
        });

        // Logout
        server.createContext("/api/auth/logout", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                com.hctamlyniv.SpotifyAuth.logout();
                exchange.sendResponseHeaders(204, -1);
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Logout: " + e.getMessage());
            }
        });

        // Statisches Serving (playlist.html und alle Dateien im Frontend-Verzeichnis)
        server.createContext("/", exchange -> {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur GET erlaubt");
                    return;
                }

                String rawPath = exchange.getRequestURI().getPath();
                // Standard: "/" -> playlist.html
                String requested = rawPath.equals("/") ? "home.html" : rawPath.substring(1);

                // Log generated links in IDE when playlist page is requested with id
                if ("playlist.html".equals(requested)) {
                    String q = exchange.getRequestURI().getQuery();
                    if (q != null) {
                        String idParam = null;
                        for (String p : q.split("&")) {
                            if (p.startsWith("id=")) {
                                idParam = p.substring(3);
                                break;
                            }
                        }
                        if (idParam != null && !idParam.isBlank()) {
                            System.out.println("[PAGE] Playlist page: http://localhost:" + port + "/playlist.html?id=" + idParam);
                            System.out.println("[PAGE] API link:      http://localhost:" + port + "/api/playlist?id=" + idParam);
                        }
                    }
                }

                // Sichere Auflösung des Pfads (keine ".."-Traversal)
                Path base = FRONTEND_BASE.toAbsolutePath().normalize();
                Path file = base.resolve(requested).normalize();
                if (!file.startsWith(base) || !Files.exists(file) || Files.isDirectory(file)) {
                    sendError(exchange, 404, "Datei nicht gefunden");
                    return;
                }

                byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().add("Content-Type", contentTypeFor(file));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Laden der Datei: " + e.getMessage());
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();
        System.out.println("Frontend läuft auf http://localhost:" + port + "/");
    }

    private static void addCorsHeaders(Headers h) {
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String contentTypeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

}