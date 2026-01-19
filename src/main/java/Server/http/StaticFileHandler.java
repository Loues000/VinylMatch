package Server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves static files from a base directory with path traversal protection.
 */
public class StaticFileHandler implements HttpHandler {

    private final Path baseDir;
    private final String defaultFile;
    private final int port;

    public StaticFileHandler(Path baseDir, String defaultFile, int port) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.defaultFile = defaultFile;
        this.port = port;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendError(exchange, 405, "Nur GET erlaubt");
                return;
            }

            String rawPath = exchange.getRequestURI().getPath();
            String requested = rawPath.equals("/") ? defaultFile : rawPath.substring(1);

            // Log generated links when playlist page is requested with id
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

            // Secure path resolution (no ".." traversal)
            Path file = baseDir.resolve(requested).normalize();
            if (!file.startsWith(baseDir) || !Files.exists(file) || Files.isDirectory(file)) {
                HttpUtils.sendError(exchange, 404, "Datei nicht gefunden");
                return;
            }

            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", contentTypeFor(file));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            HttpUtils.sendError(exchange, 500, "Fehler beim Laden der Datei: " + e.getMessage());
        }
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
}
