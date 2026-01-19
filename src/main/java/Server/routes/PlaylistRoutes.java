package Server.routes;

import Server.PlaylistData;
import Server.PlaylistSummary;
import Server.UserPlaylistsResponse;
import Server.cache.PlaylistCache;
import Server.cache.PlaylistCacheKey;
import Server.http.HttpUtils;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.ReceivingData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Handles playlist-related API routes.
 */
public class PlaylistRoutes {

    private final int port;
    private final PlaylistCache playlistCache;
    private final Supplier<DiscogsService> discogsServiceSupplier;
    private final AuthRoutes authRoutes;

    public PlaylistRoutes(int port, PlaylistCache playlistCache, Supplier<DiscogsService> discogsServiceSupplier, AuthRoutes authRoutes) {
        this.port = port;
        this.playlistCache = playlistCache;
        this.discogsServiceSupplier = discogsServiceSupplier;
        this.authRoutes = authRoutes;
    }

    public void register(HttpServer server) {
        server.createContext("/api/playlist", this::handleGetPlaylist);
        server.createContext("/api/user/playlists", this::handleGetUserPlaylists);
    }

    private void handleGetPlaylist(HttpExchange exchange) throws IOException {
        PlaylistCacheKey cacheKey = null;
        try {
            HttpUtils.addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            String id = params.get("id");
            if (id == null || id.isBlank()) {
                HttpUtils.sendError(exchange, 400, "Missing or invalid 'id' query parameter");
                return;
            }

            int offset = 0;
            String offsetParam = params.get("offset");
            if (offsetParam != null && !offsetParam.isBlank()) {
                try {
                    offset = Math.max(0, Integer.parseInt(offsetParam));
                } catch (NumberFormatException e) {
                    HttpUtils.sendError(exchange, 400, "Invalid 'offset' query parameter");
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
                    HttpUtils.sendError(exchange, 400, "Invalid 'limit' query parameter");
                    return;
                }
            }

            String limitLog = (limit > 0) ? String.valueOf(limit) : "all";
            System.out.println("[API] Requested: http://localhost:" + port + "/api/playlist?id=" + id + "&offset=" + offset + "&limit=" + limitLog);

            // Get token using session-aware auth
            String token = authRoutes.getAccessToken(exchange);
            if (token == null || token.isBlank()) {
                HttpUtils.sendError(exchange, 401, "Spotify-Login erforderlich. Bitte über den Login-Button anmelden.");
                return;
            }

            String userSignature = authRoutes.getUserSignature(exchange);
            playlistCache.invalidateForAuthChange(userSignature);
            cacheKey = new PlaylistCacheKey(id, userSignature, offset, limit);
            DiscogsService discogsService = discogsServiceSupplier.get();

            PlaylistData playlistData = playlistCache.lookup(cacheKey);
            if (playlistData == null) {
                ReceivingData rd = new ReceivingData(token, id, discogsService);
                playlistData = rd.loadPlaylistData(offset, limit);
                if (playlistData == null) {
                    playlistCache.remove(cacheKey);
                    HttpUtils.sendError(exchange, 500, "Failed to load playlist data");
                    return;
                }
                playlistCache.store(cacheKey, playlistData);
            }

            HttpUtils.sendJson(exchange, 200, playlistData);
        } catch (Exception e) {
            if (cacheKey != null) {
                playlistCache.remove(cacheKey);
            }
            HttpUtils.sendError(exchange, 500, "Error loading playlist: " + e.getMessage());
        }
    }

    private void handleGetUserPlaylists(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            String token = authRoutes.getAccessToken(exchange);
            if (token == null || token.isBlank()) {
                HttpUtils.sendError(exchange, 401, "Spotify-Login erforderlich. Bitte über den Login-Button anmelden.");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            int offset = 0;
            String offsetParam = params.get("offset");
            if (offsetParam != null && !offsetParam.isBlank()) {
                try {
                    offset = Math.max(0, Integer.parseInt(offsetParam));
                } catch (NumberFormatException e) {
                    HttpUtils.sendError(exchange, 400, "Invalid 'offset' query parameter");
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
                    HttpUtils.sendError(exchange, 400, "Invalid 'limit' query parameter");
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
            HttpUtils.sendJson(exchange, 200, payload);
        } catch (SpotifyWebApiException e) {
            HttpUtils.sendError(exchange, 502, "Spotify API error: " + e.getMessage());
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Error loading user playlists: " + e.getMessage());
        }
    }
}
