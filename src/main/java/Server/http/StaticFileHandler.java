package Server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves static files from a base directory with path traversal protection.
 */
public class StaticFileHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class);

    private final Path baseDir;
    private final String defaultFile;
    private final int port;
    private final URI canonicalLoopbackBase;

    public StaticFileHandler(Path baseDir, String defaultFile, int port, URI canonicalLoopbackBase) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.defaultFile = defaultFile;
        this.port = port;
        this.canonicalLoopbackBase = canonicalizeLoopbackBase(canonicalLoopbackBase);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendText(exchange, 405, "Only GET is supported");
                return;
            }

            if (maybeRedirectToCanonicalLoopbackHost(exchange)) {
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
                        String scheme = HttpUtils.isSecureRequest(exchange) ? "https" : "http";
                        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
                        String host = (hostHeader == null || hostHeader.isBlank()) ? "localhost:" + port : hostHeader.trim();
                        log.info("Playlist page: {}://{}/playlist.html?id={}", scheme, host, idParam);
                        log.info("API link:      {}://{}/api/playlist?id={}", scheme, host, idParam);
                    }
                }
            }

            // Secure path resolution (no ".." traversal)
            Path file = baseDir.resolve(requested).normalize();
            if (!file.startsWith(baseDir) || !Files.exists(file) || Files.isDirectory(file)) {
                HttpUtils.sendText(exchange, 404, "Not found");
                return;
            }

            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", contentTypeFor(file));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            HttpUtils.sendText(exchange, 500, "Failed to load file");
        }
    }

    private boolean maybeRedirectToCanonicalLoopbackHost(HttpExchange exchange) throws IOException {
        if (canonicalLoopbackBase == null || exchange == null) return false;

        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
        if (hostHeader == null || hostHeader.isBlank()) return false;

        String scheme = HttpUtils.isSecureRequest(exchange) ? "https" : "http";
        URI requestBase;
        try {
            requestBase = URI.create(scheme + "://" + hostHeader.trim());
        } catch (Exception e) {
            return false;
        }

        String requestHost = requestBase.getHost();
        String canonicalHost = canonicalLoopbackBase.getHost();
        if (!isLoopbackHost(requestHost) || !isLoopbackHost(canonicalHost)) return false;
        if (requestHost.equalsIgnoreCase(canonicalHost)) return false;

        int port = requestBase.getPort() > 0 ? requestBase.getPort() : exchange.getLocalAddress().getPort();
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();

        URI redirect;
        try {
            redirect = new URI(scheme, null, canonicalHost, port, path, query, null);
        } catch (URISyntaxException e) {
            return false;
        }

        exchange.getResponseHeaders().set("Location", redirect.toString());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
        return true;
    }

    private static URI canonicalizeLoopbackBase(URI uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        if (!isLoopbackHost(host)) return null;
        return uri;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
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
