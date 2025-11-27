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
import java.time.Duration;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import com.hctamlyniv.ReceivingData;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;

public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FRONTEND_BASE = Paths.get("src/main/frontend");
    private static final Duration PLAYLIST_CACHE_TTL = Duration.ofMinutes(5);
    private static final Path PLAYLIST_CACHE_DIR = Paths.get("cache", "playlists");
    private static final ConcurrentHashMap<CacheKey, CacheEntry> PLAYLIST_CACHE = new ConcurrentHashMap<>();
    private static volatile String lastAuthSignature = null;
    private static final Map<String, com.hctamlyniv.DiscogsService> DISCOGS_SERVICES = new ConcurrentHashMap<>();
    private static final Map<String, DiscogsSession> DISCOGS_SESSIONS = new ConcurrentHashMap<>();
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public static void start() throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8888"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        try {
            Files.createDirectories(PLAYLIST_CACHE_DIR);
        } catch (IOException e) {
            System.err.println("[CACHE] Konnte Cache-Verzeichnis nicht erstellen: " + e.getMessage());
        }

        // API-Route
        server.createContext("/api/playlist", exchange -> {
            CacheKey cacheKey = null;
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

                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                String id = params.get("id");
                if (id == null || id.isBlank()) {
                    sendError(exchange, 400, "Missing or invalid 'id' query parameter");
                    return;
                }

                int offset = 0;
                String offsetParam = params.get("offset");
                if (offsetParam != null && !offsetParam.isBlank()) {
                    try {
                        offset = Math.max(0, Integer.parseInt(offsetParam));
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Invalid 'offset' query parameter");
                        return;
                    }
                }

                int limit = -1;
                String limitParam = params.get("limit");
                if (limitParam != null && !limitParam.isBlank()) {
                    try {
                        int parsedLimit = Integer.parseInt(limitParam);
                        if (parsedLimit > 0) {
                            limit = Math.min(parsedLimit, 500);
                        } else {
                            limit = -1;
                        }
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Invalid 'limit' query parameter");
                        return;
                    }
                }

                String limitLog = (limit > 0) ? String.valueOf(limit) : "all";
                System.out.println("[API] Requested: http://localhost:" + port + "/api/playlist?id=" + id + "&offset=" + offset + "&limit=" + limitLog);

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

                String userSignature = deriveUserSignature(token);
                invalidateCacheForAuthChange(userSignature);
                cacheKey = new CacheKey(id, userSignature, offset, limit);
                com.hctamlyniv.DiscogsService discogsService = getDiscogsService();

                PlaylistData playlistData = lookupCachedPlaylist(cacheKey);
                if (playlistData == null) {
                    ReceivingData rd = new ReceivingData(token, id, discogsService);
                    playlistData = rd.loadPlaylistData(offset, limit);
                    if (playlistData == null) {
                        PLAYLIST_CACHE.remove(cacheKey);
                        deleteSnapshot(cacheKey);
                        sendError(exchange, 500, "Failed to load playlist data");
                        return;
                    }
                    storeInCache(cacheKey, playlistData);
                }
                String json = MAPPER.writeValueAsString(playlistData);
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception e) {
                if (cacheKey != null) {
                    PLAYLIST_CACHE.remove(cacheKey);
                    deleteSnapshot(cacheKey);
                }
                sendError(exchange, 500, "Error loading playlist: " + e.getMessage());
            }
        });

        server.createContext("/api/user/playlists", exchange -> {
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
                String token = null;
                if (com.hctamlyniv.SpotifyAuth.isUserLoggedIn()) {
                    try { com.hctamlyniv.SpotifyAuth.refreshAccessToken(); } catch (Exception ignored) {}
                    token = com.hctamlyniv.SpotifyAuth.getAccessToken();
                }
                if (token == null || token.isBlank()) {
                    sendError(exchange, 401, "Spotify-Login erforderlich. Bitte über den Login-Button anmelden.");
                    return;
                }
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                int offset = 0;
                String offsetParam = params.get("offset");
                if (offsetParam != null && !offsetParam.isBlank()) {
                    try {
                        offset = Math.max(0, Integer.parseInt(offsetParam));
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Invalid 'offset' query parameter");
                        return;
                    }
                }
                int limit = 50;
                String limitParam = params.get("limit");
                if (limitParam != null && !limitParam.isBlank()) {
                    try {
                        int parsedLimit = Integer.parseInt(limitParam);
                        if (parsedLimit > 0) {
                            limit = Math.min(parsedLimit, 50);
                        }
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Invalid 'limit' query parameter");
                        return;
                    }
                }

                SpotifyApi spotifyApi = new SpotifyApi.Builder().setAccessToken(token).build();
                Paging<PlaylistSimplified> page = spotifyApi.getListOfCurrentUsersPlaylists()
                        .offset(offset)
                        .limit(limit)
                        .build()
                        .execute();

                PlaylistSimplified[] items = page.getItems();
                List<PlaylistSummary> summaries = new ArrayList<>();
                if (items != null) {
                    for (PlaylistSimplified item : items) {
                        if (item == null || item.getId() == null) {
                            continue;
                        }
                        String coverUrl = null;
                        if (item.getImages() != null && item.getImages().length > 0) {
                            coverUrl = item.getImages()[0].getUrl();
                        }
                        Integer trackCount = null;
                        if (item.getTracks() != null) {
                            trackCount = item.getTracks().getTotal();
                        }
                        String owner = (item.getOwner() != null) ? item.getOwner().getDisplayName() : null;
                        summaries.add(new PlaylistSummary(item.getId(), item.getName(), coverUrl, trackCount, owner));
                    }
                }

                int total = page.getTotal();
                UserPlaylistsResponse payload = new UserPlaylistsResponse(summaries, total, offset, limit);
                String json = MAPPER.writeValueAsString(payload);
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (SpotifyWebApiException e) {
                sendError(exchange, 502, "Spotify API error: " + e.getMessage());
            } catch (Exception e) {
                sendError(exchange, 500, "Error loading user playlists: " + e.getMessage());
            }
        });

        // Discogs-Suche (Batch, asynchron zum Playlist-Laden)
        server.createContext("/api/discogs/batch", exchange -> {
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

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                java.util.Map payload = MAPPER.readValue(body, java.util.Map.class);
                Object tracksObj = payload.get("tracks");
                if (!(tracksObj instanceof java.util.List<?> tracksList) || tracksList.isEmpty()) {
                    sendError(exchange, 400, "Payload muss ein 'tracks'-Array enthalten");
                    return;
                }

                com.hctamlyniv.DiscogsService discogs = getDiscogsService();
                if (discogs == null) {
                    sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                    return;
                }

                java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
                for (Object entry : tracksList) {
                    if (!(entry instanceof java.util.Map<?, ?> track)) {
                        continue;
                    }
                    String key = stringValue(track.get("key"));
                    Integer index = intValue(track.get("index"));
                    String artist = stringValue(track.get("artist"));
                    String album = stringValue(track.get("album"));
                    Integer year = intValue(track.get("releaseYear"));
                    String trackTitle = stringValue(track.get("track"));
                    String barcode = stringValue(track.get("barcode"));

                    java.util.Map<String, Object> resultEntry = new java.util.HashMap<>();
                    if (key != null) {
                        resultEntry.put("key", key);
                    }
                    if (index != null) {
                        resultEntry.put("index", index);
                    }

                    if (artist == null || album == null) {
                        resultEntry.put("url", null);
                        resultEntry.put("cacheHit", false);
                        results.add(resultEntry);
                        continue;
                    }

                    java.util.Optional<String> cached = discogs.peekCachedUri(artist, album, year, barcode);
                    boolean cacheHit = cached.isPresent();
                    java.util.Optional<String> urlOpt = cached.isPresent()
                            ? cached
                            : discogs.findAlbumUri(artist, album, year, trackTitle, barcode);

                    resultEntry.put("cacheHit", cacheHit);
                    resultEntry.put("url", urlOpt.orElse(null));
                    results.add(resultEntry);
                }

                String json = MAPPER.writeValueAsString(java.util.Map.of("results", results));
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler bei Discogs-Batch: " + e.getMessage());
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

                com.hctamlyniv.DiscogsService discogs = getDiscogsService();
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

        // Discogs-Session-Status
        server.createContext("/api/discogs/status", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur GET erlaubt");
                    return;
                }
                DiscogsSession session = getDiscogsSession(exchange);
                Map<String, Object> payload = new HashMap<>();
                payload.put("loggedIn", session != null && session.username() != null);
                if (session != null) {
                    payload.put("username", session.username());
                    payload.put("name", session.displayName());
                }
                String json = MAPPER.writeValueAsString(payload);
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler bei Discogs-Status: " + e.getMessage());
            }
        });

        // Discogs-Login via User-Token
        server.createContext("/api/discogs/login", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map payload = MAPPER.readValue(body, Map.class);
                String token = stringValue(payload.get("token"));
                String userAgent = stringValue(payload.getOrDefault("userAgent", com.hctamlyniv.Config.getDiscogsUserAgent()));
                if (token == null || token.isBlank()) {
                    sendError(exchange, 400, "Discogs-User-Token erforderlich");
                    return;
                }
                String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
                com.hctamlyniv.DiscogsService service = getDiscogsService(token, ua);
                com.hctamlyniv.DiscogsService.DiscogsProfile profile = service.fetchProfile().orElse(null);
                if (profile == null || profile.username() == null) {
                    sendError(exchange, 401, "Discogs-Token ungültig oder Zugriff verweigert");
                    return;
                }
                String sessionId = java.util.UUID.randomUUID().toString();
                DiscogsSession session = new DiscogsSession(sessionId, token, ua, profile.username(), profile.name());
                DISCOGS_SESSIONS.put(sessionId, session);
                exchange.getResponseHeaders().add("Set-Cookie", "discogs_session=" + sessionId + "; Path=/; Max-Age=86400");
                Map<String, Object> response = new HashMap<>();
                response.put("username", profile.username());
                response.put("name", profile.name());
                byte[] out = MAPPER.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Discogs-Login: " + e.getMessage());
            }
        });

        // Discogs-Logout
        server.createContext("/api/discogs/logout", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                String sessionId = cookieValue(exchange, "discogs_session");
                if (sessionId != null) {
                    DISCOGS_SESSIONS.remove(sessionId);
                }
                exchange.getResponseHeaders().add("Set-Cookie", "discogs_session=; Path=/; Max-Age=0");
                exchange.sendResponseHeaders(204, -1);
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Discogs-Logout: " + e.getMessage());
            }
        });

        // Wunschliste abrufen (kleines Preview)
        server.createContext("/api/discogs/wishlist", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur GET erlaubt");
                    return;
                }
                DiscogsSession session = getDiscogsSession(exchange);
                if (session == null || session.username() == null) {
                    sendError(exchange, 401, "Discogs-Login erforderlich");
                    return;
                }
                int limit = 12;
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                if (params.containsKey("limit")) {
                    try {
                        int parsed = Integer.parseInt(params.get("limit"));
                        if (parsed > 0 && parsed <= 50) limit = parsed;
                    } catch (NumberFormatException ignored) {}
                }
                com.hctamlyniv.DiscogsService service = getDiscogsService(session.token(), session.userAgent());
                com.hctamlyniv.DiscogsService.WishlistResult wishlist = service.fetchWishlist(session.username(), 1, limit);
                byte[] out = MAPPER.writeValueAsBytes(wishlist);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Laden der Wunschliste: " + e.getMessage());
            }
        });

        // Wunschliste hinzufügen
        server.createContext("/api/discogs/wishlist/add", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                DiscogsSession session = getDiscogsSession(exchange);
                if (session == null || session.username() == null) {
                    sendError(exchange, 401, "Discogs-Login erforderlich");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map payload = MAPPER.readValue(body, Map.class);
                String url = stringValue(payload.get("url"));
                if (url == null || url.isBlank()) {
                    sendError(exchange, 400, "Parameter 'url' erforderlich");
                    return;
                }
                com.hctamlyniv.DiscogsService service = getDiscogsService(session.token(), session.userAgent());
                Integer releaseId = service.resolveReleaseIdFromUrl(url).orElse(null);
                if (releaseId == null) {
                    sendError(exchange, 422, "Konnte Release-ID aus URL nicht bestimmen");
                    return;
                }
                boolean added = service.addToWantlist(session.username(), releaseId);
                Map<String, Object> response = Map.of(
                        "added", added,
                        "releaseId", releaseId
                );
                byte[] out = MAPPER.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(added ? 200 : 409, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Hinzufügen zur Wunschliste: " + e.getMessage());
            }
        });

        // Besitz-/Wunschlisten-Status für mehrere Releases
        server.createContext("/api/discogs/library-status", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                DiscogsSession session = getDiscogsSession(exchange);
                if (session == null || session.username() == null) {
                    sendError(exchange, 401, "Discogs-Login erforderlich");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map payload = MAPPER.readValue(body, Map.class);
                Object urlsObj = payload.get("urls");
                if (!(urlsObj instanceof List<?> list) || list.isEmpty()) {
                    sendError(exchange, 400, "Payload muss 'urls' enthalten");
                    return;
                }
                List<String> urls = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) {
                        urls.add(s.trim());
                    }
                }
                if (urls.isEmpty()) {
                    sendError(exchange, 400, "Keine gültigen URLs gefunden");
                    return;
                }
                com.hctamlyniv.DiscogsService service = getDiscogsService(session.token(), session.userAgent());
                Map<String, Integer> resolved = new HashMap<>();
                for (String url : urls) {
                    service.resolveReleaseIdFromUrl(url).ifPresent(id -> resolved.put(url, id));
                }
                Map<Integer, com.hctamlyniv.DiscogsService.LibraryFlags> flags = service.lookupLibraryFlags(session.username(), new java.util.HashSet<>(resolved.values()));
                List<Map<String, Object>> results = new ArrayList<>();
                for (String url : urls) {
                    Integer id = resolved.get(url);
                    com.hctamlyniv.DiscogsService.LibraryFlags flag = (id == null) ? null : flags.get(id);
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("url", url);
                    entry.put("releaseId", id);
                    entry.put("inWishlist", flag != null && flag.inWishlist());
                    entry.put("inCollection", flag != null && flag.inCollection());
                    results.add(entry);
                }
                byte[] out = MAPPER.writeValueAsBytes(Map.of("results", results));
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Laden des Library-Status: " + e.getMessage());
            }
        });

        // Dev: Curation candidates for manual Discogs link verification
        server.createContext("/api/discogs/curation/candidates", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map payload = MAPPER.readValue(body, Map.class);
                String artist = stringValue(payload.get("artist"));
                String album = stringValue(payload.get("album"));
                Integer year = intValue(payload.get("year"));
                String trackTitle = stringValue(payload.get("trackTitle"));
                com.hctamlyniv.DiscogsService discogs = getDiscogsService();
                if (discogs == null) {
                    sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                    return;
                }
                java.util.List<com.hctamlyniv.DiscogsService.CurationCandidate> candidates = discogs.fetchCurationCandidates(
                        artist, album, year, trackTitle, 4);
                byte[] out = MAPPER.writeValueAsBytes(Map.of("candidates", candidates));
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Laden der Curation-Kandidaten: " + e.getMessage());
            }
        });

        // Dev: Persist curated Discogs links for better matching
        server.createContext("/api/discogs/curation/save", exchange -> {
            try {
                addCorsHeaders(exchange.getResponseHeaders());
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Nur POST erlaubt");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map payload = MAPPER.readValue(body, Map.class);
                String artist = stringValue(payload.get("artist"));
                String album = stringValue(payload.get("album"));
                Integer year = intValue(payload.get("year"));
                String trackTitle = stringValue(payload.get("trackTitle"));
                String barcode = stringValue(payload.get("barcode"));
                String url = stringValue(payload.get("url"));
                String thumb = stringValue(payload.get("thumb"));
                if (url == null || url.isBlank()) {
                    sendError(exchange, 400, "Parameter 'url' erforderlich");
                    return;
                }
                com.hctamlyniv.DiscogsService discogs = getDiscogsService();
                if (discogs == null) {
                    sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                    return;
                }
                com.hctamlyniv.DiscogsService.CuratedLink saved = discogs.saveCuratedLink(artist, album, year, trackTitle,
                        barcode, url, thumb);
                byte[] out = MAPPER.writeValueAsBytes(Map.of("saved", true, "entry", saved));
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                sendError(exchange, 500, "Fehler beim Speichern des kuratierten Links: " + e.getMessage());
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
                invalidateCacheForAuthChange(null);
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

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private static com.hctamlyniv.DiscogsService getDiscogsService() {
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
        return DISCOGS_SERVICES.computeIfAbsent(key, k -> new com.hctamlyniv.DiscogsService(tokenFinal, userAgentFinal));
    }

    private static com.hctamlyniv.DiscogsService getDiscogsService(String token, String userAgent) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
        final String key = token + "|" + ua;
        return DISCOGS_SERVICES.computeIfAbsent(key, k -> new com.hctamlyniv.DiscogsService(token, ua));
    }

    private static DiscogsSession getDiscogsSession(HttpExchange exchange) {
        String sessionId = cookieValue(exchange, "discogs_session");
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return DISCOGS_SESSIONS.get(sessionId);
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        if (exchange == null || name == null) {
            return null;
        }
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String header : cookies) {
            if (header == null) continue;
            String[] parts = header.split(";\\s*");
            for (String part : parts) {
                int idx = part.indexOf('=');
                if (idx <= 0) continue;
                String key = part.substring(0, idx);
                String value = part.substring(idx + 1);
                if (name.equals(key)) {
                    return value;
                }
            }
        }
        return null;
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

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key;
            String value;
            if (idx >= 0) {
                key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            } else {
                key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = "";
            }
            params.put(key, value);
        }
        return params;
    }

    private static PlaylistData lookupCachedPlaylist(CacheKey key) {
        CacheEntry entry = PLAYLIST_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (entry != null) {
            if (entry.isExpired(now)) {
                PLAYLIST_CACHE.remove(key, entry);
                deleteSnapshot(key);
                entry = null;
            } else {
                return entry.playlistData();
            }
        }
        CacheEntry snapshotEntry = readSnapshot(key);
        if (snapshotEntry != null) {
            PLAYLIST_CACHE.put(key, snapshotEntry);
            return snapshotEntry.playlistData();
        }
        return null;
    }

    private static void storeInCache(CacheKey key, PlaylistData playlistData) {
        long expiresAt = System.currentTimeMillis() + PLAYLIST_CACHE_TTL.toMillis();
        CacheEntry entry = new CacheEntry(playlistData, expiresAt);
        PLAYLIST_CACHE.put(key, entry);
        writeSnapshot(key, entry);
    }

    private static void invalidateCacheForAuthChange(String newSignature) {
        String normalized = (newSignature == null) ? "" : newSignature;
        String previous = lastAuthSignature;
        if (!Objects.equals(previous, normalized)) {
            PLAYLIST_CACHE.clear();
            purgeAllSnapshots();
            lastAuthSignature = normalized;
        }
    }

    private static String deriveUserSignature(String fallbackToken) {
        String refreshToken = com.hctamlyniv.SpotifyAuth.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            return refreshToken;
        }
        String accessToken = com.hctamlyniv.SpotifyAuth.getAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            return accessToken;
        }
        return (fallbackToken != null) ? fallbackToken : "";
    }

    private static CacheEntry readSnapshot(CacheKey key) {
        Path path = snapshotPath(key);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            CacheSnapshot snapshot = MAPPER.readValue(bytes, CacheSnapshot.class);
            long now = System.currentTimeMillis();
            if (snapshot.expiresAtMillis() <= now) {
                Files.deleteIfExists(path);
                return null;
            }
            return new CacheEntry(snapshot.playlistData(), snapshot.expiresAtMillis());
        } catch (IOException e) {
            System.err.println("[CACHE] Konnte Snapshot nicht laden: " + e.getMessage());
            return null;
        }
    }

    private static void writeSnapshot(CacheKey key, CacheEntry entry) {
        try {
            Files.createDirectories(PLAYLIST_CACHE_DIR);
            CacheSnapshot snapshot = new CacheSnapshot(entry.playlistData(), entry.expiresAtMillis());
            byte[] bytes = MAPPER.writeValueAsBytes(snapshot);
            Files.write(snapshotPath(key), bytes);
        } catch (IOException e) {
            System.err.println("[CACHE] Konnte Snapshot nicht speichern: " + e.getMessage());
        }
    }

    private static void deleteSnapshot(CacheKey key) {
        try {
            Files.deleteIfExists(snapshotPath(key));
        } catch (IOException e) {
            System.err.println("[CACHE] Konnte Snapshot nicht löschen: " + e.getMessage());
        }
    }

    private static void purgeAllSnapshots() {
        try {
            if (!Files.exists(PLAYLIST_CACHE_DIR)) {
                return;
            }
            try (var stream = Files.list(PLAYLIST_CACHE_DIR)) {
                stream.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("[CACHE] Konnte Cache-Verzeichnis nicht bereinigen: " + e.getMessage());
        }
    }

    private static Path snapshotPath(CacheKey key) {
        return PLAYLIST_CACHE_DIR.resolve(hashCacheKey(key) + ".json");
    }

    private static String hashCacheKey(CacheKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((key.playlistId() == null ? "" : key.playlistId()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update((key.userSignature() == null ? "" : key.userSignature()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.offset()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.limit()).getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record CacheKey(String playlistId, String userSignature, int offset, int limit) {
    }

    private record CacheEntry(PlaylistData playlistData, long expiresAtMillis) {
        boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }

    private record CacheSnapshot(PlaylistData playlistData, long expiresAtMillis) {
    }

    private record DiscogsSession(String sessionId, String token, String userAgent, String username, String displayName) {
    }
}
