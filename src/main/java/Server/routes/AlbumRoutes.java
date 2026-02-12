package Server.routes;

import Server.AlbumExtractor;
import Server.AlbumGroup;
import Server.TrackData;
import Server.http.ApiFilters;
import Server.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Handles album-related API routes.
 * Provides endpoints for extracting and grouping albums from playlist tracks.
 */
public class AlbumRoutes {

    private static final Logger log = LoggerFactory.getLogger(AlbumRoutes.class);

    private final AlbumExtractor albumExtractor;

    public AlbumRoutes() {
        this.albumExtractor = new AlbumExtractor();
    }

    public void register(HttpServer server) {
        server.createContext("/api/albums/extract", this::handleExtract).getFilters().addAll(
                java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
    }

    private void handleExtract(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            
            Object tracksObj = payload.get("tracks");
            if (!(tracksObj instanceof List<?> tracksList)) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Payload must contain a 'tracks' array");
                return;
            }

            List<TrackData> tracks = HttpUtils.getMapper().convertValue(
                    tracksList, 
                    HttpUtils.getMapper().getTypeFactory().constructCollectionType(List.class, TrackData.class)
            );

            log.info("Extracting albums from {} tracks", tracks.size());

            List<AlbumGroup> albums = albumExtractor.extractAlbums(tracks);

            log.info("Extracted {} unique albums from {} tracks", albums.size(), tracks.size());

            HttpUtils.sendJson(exchange, 200, Map.of(
                    "albums", albums,
                    "totalTracks", tracks.size(),
                    "totalAlbums", albums.size()
            ));
            
        } catch (Exception e) {
            log.warn("Album extraction failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "album_extraction_failed", "Failed to extract albums from tracks");
        }
    }
}
