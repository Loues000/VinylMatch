package Server.routes;

import Server.http.HttpUtils;
import Server.session.DiscogsSession;
import Server.session.DiscogsSessionStore;
import com.hctamlyniv.DiscogsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Handles Discogs-related API routes.
 */
public class DiscogsRoutes {

    private final Supplier<DiscogsService> defaultDiscogsSupplier;
    private final DiscogsSessionStore sessionStore;
    private final Map<String, DiscogsService> serviceCache = new ConcurrentHashMap<>();

    public DiscogsRoutes(Supplier<DiscogsService> defaultDiscogsSupplier, DiscogsSessionStore sessionStore) {
        this.defaultDiscogsSupplier = defaultDiscogsSupplier;
        this.sessionStore = sessionStore;
    }

    public void register(HttpServer server) {
        server.createContext("/api/discogs/batch", this::handleBatch);
        server.createContext("/api/discogs/search", this::handleSearch);
        server.createContext("/api/discogs/status", this::handleStatus);
        server.createContext("/api/discogs/login", this::handleLogin);
        server.createContext("/api/discogs/logout", this::handleLogout);
        server.createContext("/api/discogs/wishlist/add", this::handleWishlistAdd);
        server.createContext("/api/discogs/wishlist", this::handleWishlist);
        server.createContext("/api/discogs/library-status", this::handleLibraryStatus);
        server.createContext("/api/discogs/curation/candidates", this::handleCurationCandidates);
        server.createContext("/api/discogs/curation/save", this::handleCurationSave);
    }

    // =========================================================================
    // Batch Search
    // =========================================================================

    private void handleBatch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object tracksObj = payload.get("tracks");
            if (!(tracksObj instanceof List<?> tracksList) || tracksList.isEmpty()) {
                HttpUtils.sendError(exchange, 400, "Payload muss ein 'tracks'-Array enthalten");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Object entry : tracksList) {
                if (!(entry instanceof Map<?, ?> track)) {
                    continue;
                }
                String key = HttpUtils.stringValue(track.get("key"));
                Integer index = HttpUtils.intValue(track.get("index"));
                String artist = HttpUtils.stringValue(track.get("artist"));
                String album = HttpUtils.stringValue(track.get("album"));
                Integer year = HttpUtils.intValue(track.get("releaseYear"));
                String trackTitle = HttpUtils.stringValue(track.get("track"));
                String barcode = HttpUtils.stringValue(track.get("barcode"));

                Map<String, Object> resultEntry = new HashMap<>();
                if (key != null) resultEntry.put("key", key);
                if (index != null) resultEntry.put("index", index);

                if (artist == null || album == null) {
                    resultEntry.put("url", null);
                    resultEntry.put("cacheHit", false);
                    results.add(resultEntry);
                    continue;
                }

                Optional<String> cached = discogs.peekCachedUri(artist, album, year, barcode);
                boolean cacheHit = cached.isPresent();
                Optional<String> urlOpt = cached.isPresent()
                        ? cached
                        : discogs.findAlbumUri(artist, album, year, trackTitle, barcode);

                resultEntry.put("cacheHit", cacheHit);
                resultEntry.put("url", urlOpt.orElse(null));
                results.add(resultEntry);
            }

            HttpUtils.sendJson(exchange, 200, Map.of("results", results));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler bei Discogs-Batch: " + e.getMessage());
        }
    }

    // =========================================================================
    // Single Search
    // =========================================================================

    private void handleSearch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = parseYear(payload.get("releaseYear"));
            String trackTitle = HttpUtils.stringValue(payload.get("track"));

            if (artist == null || album == null) {
                HttpUtils.sendError(exchange, 400, "Felder 'artist' und 'album' sind erforderlich");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                return;
            }

            Optional<String> urlOpt = discogs.findAlbumUri(artist, album, year, trackTitle);
            if (urlOpt.isEmpty()) {
                HttpUtils.sendError(exchange, 404, "Kein Discogs-Treffer gefunden");
                return;
            }

            HttpUtils.sendJson(exchange, 200, Map.of("url", urlOpt.get()));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler bei Discogs-Suche: " + e.getMessage());
        }
    }

    // =========================================================================
    // Session Management
    // =========================================================================

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            Map<String, Object> payload = new HashMap<>();
            payload.put("loggedIn", session != null && session.username() != null);
            if (session != null) {
                payload.put("username", session.username());
                payload.put("name", session.displayName());
            }
            HttpUtils.sendJson(exchange, 200, payload);
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler bei Discogs-Status: " + e.getMessage());
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String token = HttpUtils.stringValue(payload.get("token"));
            Object userAgentObj = payload.get("userAgent");
            String userAgent = userAgentObj != null ? HttpUtils.stringValue(userAgentObj) : com.hctamlyniv.Config.getDiscogsUserAgent();

            if (token == null || token.isBlank()) {
                HttpUtils.sendError(exchange, 400, "Discogs-User-Token erforderlich");
                return;
            }

            String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
            DiscogsService service = getOrCreateService(token, ua);
            DiscogsService.DiscogsProfile profile = service.fetchProfile().orElse(null);
            if (profile == null || profile.username() == null) {
                HttpUtils.sendError(exchange, 401, "Discogs-Token ungültig oder Zugriff verweigert");
                return;
            }

            DiscogsSession session = sessionStore.createSession(exchange, token, ua, profile.username(), profile.name());
            Map<String, Object> response = new HashMap<>();
            response.put("username", session.username());
            response.put("name", session.displayName());
            HttpUtils.sendJson(exchange, 200, response);
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Discogs-Login: " + e.getMessage());
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            sessionStore.destroySession(exchange);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Discogs-Logout: " + e.getMessage());
        }
    }

    // =========================================================================
    // Wishlist Operations
    // =========================================================================

    private void handleWishlist(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendError(exchange, 401, "Discogs-Login erforderlich");
                return;
            }

            int limit = 12;
            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            if (params.containsKey("limit")) {
                try {
                    int parsed = Integer.parseInt(params.get("limit"));
                    if (parsed > 0 && parsed <= 50) limit = parsed;
                } catch (NumberFormatException ignored) {}
            }

            DiscogsService service = getOrCreateService(session.token(), session.userAgent());
            DiscogsService.WishlistResult wishlist = service.fetchWishlist(session.username(), 1, limit);
            HttpUtils.sendJson(exchange, 200, wishlist);
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Laden der Wunschliste: " + e.getMessage());
        }
    }

    private void handleWishlistAdd(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendError(exchange, 401, "Discogs-Login erforderlich");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String url = HttpUtils.stringValue(payload.get("url"));
            if (!HttpUtils.isDiscogsWebUrl(url)) {
                HttpUtils.sendError(exchange, 400, "Parameter 'url' erforderlich");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.userAgent());
            Integer releaseId = service.resolveReleaseIdFromUrl(url).orElse(null);
            if (releaseId == null) {
                HttpUtils.sendError(exchange, 422, "Konnte Release-ID aus URL nicht bestimmen");
                return;
            }

            boolean added = service.addToWantlist(session.username(), releaseId);
            HttpUtils.sendJson(exchange, added ? 200 : 409, Map.of(
                    "added", added,
                    "releaseId", releaseId
            ));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Hinzufügen zur Wunschliste: " + e.getMessage());
        }
    }

    // =========================================================================
    // Library Status
    // =========================================================================

    private void handleLibraryStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendError(exchange, 401, "Discogs-Login erforderlich");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object urlsObj = payload.get("urls");
            if (!(urlsObj instanceof List<?> list) || list.isEmpty()) {
                HttpUtils.sendError(exchange, 400, "Payload muss 'urls' enthalten");
                return;
            }

            List<String> urls = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && HttpUtils.isDiscogsWebUrl(s)) {
                    urls.add(s.trim());
                }
            }
            if (urls.isEmpty()) {
                HttpUtils.sendError(exchange, 400, "Keine gültigen URLs gefunden");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.userAgent());
            Map<String, Integer> resolved = new HashMap<>();
            for (String url : urls) {
                service.resolveReleaseIdFromUrl(url).ifPresent(id -> resolved.put(url, id));
            }

            Map<Integer, DiscogsService.LibraryFlags> flags = service.lookupLibraryFlags(session.username(), new HashSet<>(resolved.values()));
            List<Map<String, Object>> results = new ArrayList<>();
            for (String url : urls) {
                Integer id = resolved.get(url);
                DiscogsService.LibraryFlags flag = (id == null) ? null : flags.get(id);
                Map<String, Object> entry = new HashMap<>();
                entry.put("url", url);
                entry.put("releaseId", id);
                entry.put("inWishlist", flag != null && flag.inWishlist());
                entry.put("inCollection", flag != null && flag.inCollection());
                results.add(entry);
            }

            HttpUtils.sendJson(exchange, 200, Map.of("results", results));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Laden des Library-Status: " + e.getMessage());
        }
    }

    // =========================================================================
    // Curation
    // =========================================================================

    private void handleCurationCandidates(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = HttpUtils.intValue(payload.get("year"));
            String trackTitle = HttpUtils.stringValue(payload.get("trackTitle"));

            if (artist == null && album == null && trackTitle == null) {
                HttpUtils.sendError(exchange, 400, "Ungültige Anfragedaten");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                return;
            }

            List<DiscogsService.CurationCandidate> candidates = discogs.fetchCurationCandidates(
                    artist, album, year, trackTitle, 4);
            HttpUtils.sendJson(exchange, 200, Map.of("candidates", candidates));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Laden der Curation-Kandidaten: " + e.getMessage());
        }
    }

    private void handleCurationSave(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur POST erlaubt");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = HttpUtils.intValue(payload.get("year"));
            String trackTitle = HttpUtils.stringValue(payload.get("trackTitle"));
            String barcode = HttpUtils.stringValue(payload.get("barcode"));
            String url = HttpUtils.stringValue(payload.get("url"));
            String thumb = HttpUtils.stringValue(payload.get("thumb"));

            if (!HttpUtils.isDiscogsWebUrl(url)) {
                HttpUtils.sendError(exchange, 400, "Parameter 'url' erforderlich");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendError(exchange, 503, "Discogs-Token fehlt (DISCOGS_TOKEN)");
                return;
            }

            DiscogsService.CuratedLink saved = discogs.saveCuratedLink(artist, album, year, trackTitle, barcode, url, thumb);
            HttpUtils.sendJson(exchange, 200, Map.of("saved", true, "entry", saved));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Speichern des kuratierten Links: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DiscogsService getOrCreateService(String token, String userAgent) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
        String key = token + "|" + ua;
        return serviceCache.computeIfAbsent(key, k -> new DiscogsService(token, ua));
    }

    private Integer parseYear(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String ys) {
            String ts = ys.trim();
            if (ts.length() >= 4) {
                try {
                    return Integer.parseInt(ts.substring(0, 4));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
