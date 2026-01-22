package Server.routes;

import Server.http.ApiFilters;
import Server.http.HttpUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Handles configuration endpoints for the frontend.
 */
public class ConfigRoutes {

    private static final Path VENDORS_CONFIG = Paths.get("config", "vendors.json");
    private final ObjectMapper mapper = HttpUtils.getMapper();
    private static final Logger log = LoggerFactory.getLogger(ConfigRoutes.class);

    public void register(HttpServer server) {
        server.createContext("/api/config/vendors", this::handleGetVendors).getFilters().add(ApiFilters.rateLimiting());
    }

    /**
     * Serves custom vendor configuration.
     * Returns an empty list if no custom config exists.
     */
    private void handleGetVendors(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            List<Map<String, Object>> vendors = loadVendorsConfig();
            HttpUtils.sendJson(exchange, 200, Map.of("vendors", vendors));
        } catch (Exception e) {
            log.warn("Failed to load vendors config: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "vendors_config_failed", "Failed to load vendors configuration");
        }
    }

    /**
     * Loads vendor configuration from file if it exists.
     */
    private List<Map<String, Object>> loadVendorsConfig() {
        if (!Files.exists(VENDORS_CONFIG)) {
            return List.of();
        }

        try {
            JsonNode root = mapper.readTree(VENDORS_CONFIG.toFile());
            JsonNode vendorsNode = root.get("vendors");
            if (vendorsNode == null || !vendorsNode.isArray()) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vendors = mapper.convertValue(vendorsNode, List.class);
            return vendors != null ? vendors : List.of();
        } catch (IOException e) {
            log.warn("Failed to parse vendors config: {}", e.getMessage());
            return List.of();
        }
    }
}
