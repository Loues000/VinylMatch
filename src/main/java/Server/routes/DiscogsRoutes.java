package Server.routes;

import Server.http.HttpUtils;
import Server.http.ApiFilters;
import Server.session.DiscogsSession;
import Server.session.DiscogsSessionStore;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.LibraryFlags;
import com.hctamlyniv.discogs.model.WishlistResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(DiscogsRoutes.class);

    private final Supplier<DiscogsService> defaultDiscogsSupplier;
    private final DiscogsSessionStore sessionStore;
    private final Map<String, DiscogsService> serviceCache = new ConcurrentHashMap<>();

    public DiscogsRoutes(Supplier<DiscogsService> defaultDiscogsSupplier, DiscogsSessionStore sessionStore) {
        this.defaultDiscogsSupplier = defaultDiscogsSupplier;
        this.sessionStore = sessionStore;
    }

    public void register(HttpServer server) {
        server.createContext("/api/discogs/batch", this::handleBatch).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/search", this::handleSearch).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/status", this::handleStatus).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/login", this::handleLogin).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/logout", this::handleLogout).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/wishlist/add", this::handleWishlistAdd).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/wishlist", this::handleWishlist).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/library-status", this::handleLibraryStatus).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/curation/candidates", this::handleCurationCandidates).getFilters().add(ApiFilters.rateLimiting());
        server.createContext("/api/discogs/curation/save", this::handleCurationSave).getFilters().add(ApiFilters.rateLimiting());
    }

    // =========================================================================
    // Batch Search
    // =========================================================================

    private void handleBatch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object tracksObj = payload.get("tracks");
            if (!(tracksObj instanceof List<?> tracksList) || tracksList.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Payload must contain a non-empty 'tracks' array");
                return;
            }

            DiscogsService discogs = resolveDiscogsService(exchange);

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
            log.warn("Discogs batch failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_batch_failed", "Discogs batch search failed");
        }
    }

    // =========================================================================
    // Single Search
    // =========================================================================

    private void handleSearch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = parseYear(payload.get("releaseYear"));
            String trackTitle = HttpUtils.stringValue(payload.get("track"));

            if (artist == null || album == null) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Fields 'artist' and 'album' are required");
                return;
            }

            DiscogsService discogs = resolveDiscogsService(exchange);

            Optional<String> urlOpt = discogs.findAlbumUri(artist, album, year, trackTitle);
            if (urlOpt.isEmpty()) {
                HttpUtils.sendApiError(exchange, 404, "not_found", "No Discogs match found");
                return;
            }

            HttpUtils.sendJson(exchange, 200, Map.of("url", urlOpt.get()));
        } catch (Exception e) {
            log.warn("Discogs search failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_search_failed", "Discogs search failed");
        }
    }

    // =========================================================================
    // Session Management
    // =========================================================================

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
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
            log.warn("Discogs status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_status_failed", "Failed to read Discogs status");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String token = HttpUtils.stringValue(payload.get("token"));
            Object userAgentObj = payload.get("userAgent");
            String userAgent = userAgentObj != null ? HttpUtils.stringValue(userAgentObj) : com.hctamlyniv.Config.getDiscogsUserAgent();

            if (token == null || token.isBlank()) {
                HttpUtils.sendApiError(exchange, 400, "missing_discogs_token", "Discogs user token is required");
                return;
            }

            String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
            DiscogsService service = getOrCreateService(token, ua);
            DiscogsProfile profile = service.fetchProfile().orElse(null);
            if (profile == null || profile.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_failed", "Discogs token invalid or access denied");
                return;
            }

            DiscogsSession session = sessionStore.createSession(exchange, token, ua, profile.username(), profile.name());
            Map<String, Object> response = new HashMap<>();
            response.put("username", session.username());
            response.put("name", session.displayName());
            HttpUtils.sendJson(exchange, 200, response);
        } catch (Exception e) {
            log.warn("Discogs login failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_login_failed", "Discogs login failed");
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            sessionStore.destroySession(exchange);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            log.warn("Discogs logout failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_logout_failed", "Discogs logout failed");
        }
    }

    // =========================================================================
    // Wishlist Operations
    // =========================================================================

    private void handleWishlist(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
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
            WishlistResult wishlist = service.fetchWishlist(session.username(), 1, limit);
            HttpUtils.sendJson(exchange, 200, wishlist);
        } catch (Exception e) {
            log.warn("Discogs wishlist failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_wishlist_failed", "Failed to load Discogs wishlist");
        }
    }

    private void handleWishlistAdd(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String url = HttpUtils.stringValue(payload.get("url"));
            if (!HttpUtils.isDiscogsWebUrl(url)) {
                HttpUtils.sendApiError(exchange, 400, "invalid_url", "Parameter 'url' is required");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.userAgent());
            Integer releaseId = service.resolveReleaseIdFromUrl(url).orElse(null);
            if (releaseId == null) {
                HttpUtils.sendApiError(exchange, 422, "invalid_url", "Could not resolve release ID from URL");
                return;
            }

            boolean added = service.addToWantlist(session.username(), releaseId);
            HttpUtils.sendJson(exchange, added ? 200 : 409, Map.of(
                    "added", added,
                    "releaseId", releaseId
            ));
        } catch (Exception e) {
            log.warn("Discogs wantlist add failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_wantlist_add_failed", "Failed to add to Discogs wantlist");
        }
    }

    // =========================================================================
    // Library Status
    // =========================================================================

    private void handleLibraryStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object urlsObj = payload.get("urls");
            if (!(urlsObj instanceof List<?> list) || list.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Payload must contain a non-empty 'urls' array");
                return;
            }

            List<String> urls = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && HttpUtils.isDiscogsWebUrl(s)) {
                    urls.add(s.trim());
                }
            }
            if (urls.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "No valid Discogs URLs provided");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.userAgent());
            Map<String, Integer> resolved = new HashMap<>();
            for (String url : urls) {
                service.resolveReleaseIdFromUrl(url).ifPresent(id -> resolved.put(url, id));
            }

            Map<Integer, LibraryFlags> flags = service.lookupLibraryFlags(session.username(), new HashSet<>(resolved.values()));
            List<Map<String, Object>> results = new ArrayList<>();
            for (String url : urls) {
                Integer id = resolved.get(url);
                LibraryFlags flag = (id == null) ? null : flags.get(id);
                Map<String, Object> entry = new HashMap<>();
                entry.put("url", url);
                entry.put("releaseId", id);
                entry.put("inWishlist", flag != null && flag.inWishlist());
                entry.put("inCollection", flag != null && flag.inCollection());
                results.add(entry);
            }

            HttpUtils.sendJson(exchange, 200, Map.of("results", results));
        } catch (Exception e) {
            log.warn("Discogs library status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_library_failed", "Failed to load Discogs library status");
        }
    }

    // =========================================================================
    // Curation
    // =========================================================================

    private void handleCurationCandidates(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = HttpUtils.intValue(payload.get("year"));
            String trackTitle = HttpUtils.stringValue(payload.get("trackTitle"));

            if (artist == null && album == null && trackTitle == null) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Invalid request payload");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendApiError(exchange, 503, "discogs_not_configured", "Discogs token not configured");
                return;
            }

            List<CurationCandidate> candidates = discogs.fetchCurationCandidates(
                    artist, album, year, trackTitle, 4);
            HttpUtils.sendJson(exchange, 200, Map.of("candidates", candidates));
        } catch (Exception e) {
            log.warn("Discogs curation candidates failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_curation_failed", "Failed to load curation candidates");
        }
    }

    private void handleCurationSave(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
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
                HttpUtils.sendApiError(exchange, 400, "invalid_url", "Parameter 'url' is required");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendApiError(exchange, 503, "discogs_not_configured", "Discogs token not configured");
                return;
            }

            CuratedLink saved = discogs.saveCuratedLink(artist, album, year, trackTitle, barcode, url, thumb);
            HttpUtils.sendJson(exchange, 200, Map.of("saved", true, "entry", saved));
        } catch (Exception e) {
            log.warn("Discogs curation save failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_curation_save_failed", "Failed to save curated link");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DiscogsService resolveDiscogsService(HttpExchange exchange) {
        DiscogsSession session = sessionStore.getSession(exchange);
        if (session != null && session.token() != null && !session.token().isBlank()) {
            DiscogsService fromSession = getOrCreateService(session.token(), session.userAgent());
            if (fromSession != null) {
                return fromSession;
            }
        }
        DiscogsService fallback = defaultDiscogsSupplier.get();
        return fallback != null ? fallback : new DiscogsService(null, "VinylMatch/1.0");
    }

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
