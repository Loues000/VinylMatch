package Server.routes;

import Server.http.HttpUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

    public void register(HttpServer server) {
        server.createContext("/api/config/vendors", this::handleGetVendors);
    }

    /**
     * Serves custom vendor configuration.
     * Returns an empty list if no custom config exists.
     */
    private void handleGetVendors(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            List<Map<String, Object>> vendors = loadVendorsConfig();
            HttpUtils.sendJson(exchange, 200, Map.of("vendors", vendors));
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Laden der Vendor-Konfiguration: " + e.getMessage());
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
            System.err.println("[Config] Konnte Vendors nicht laden: " + e.getMessage());
            return List.of();
        }
    }
}
