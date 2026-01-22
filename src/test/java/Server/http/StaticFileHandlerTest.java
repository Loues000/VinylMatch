package Server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void servesDefaultFileForRootPath() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "<!doctype html><h1>ok</h1>", StandardCharsets.UTF_8);
        StaticFileHandler handler = new StaticFileHandler(tempDir, "index.html", 1234, null);

        FakeExchange ex = new FakeExchange("GET", URI.create("http://127.0.0.1/"));
        handler.handle(ex);

        assertEquals(200, ex.getResponseCode());
        assertTrue(ex.getResponseHeaders().getFirst("Content-Type").startsWith("text/html"));
        assertTrue(ex.responseBodyAsString().contains("<!doctype html>"));
    }

    @Test
    void rejectsNonGetRequests() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        StaticFileHandler handler = new StaticFileHandler(tempDir, "index.html", 1234, null);

        FakeExchange ex = new FakeExchange("POST", URI.create("http://127.0.0.1/"));
        handler.handle(ex);

        assertEquals(405, ex.getResponseCode());
        assertTrue(ex.responseBodyAsString().contains("Only GET is supported"));
    }

    @Test
    void blocksPathTraversalAndMissingFiles() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        StaticFileHandler handler = new StaticFileHandler(tempDir, "index.html", 1234, null);

        FakeExchange traversal = new FakeExchange("GET", URI.create("http://127.0.0.1/../secret.txt"));
        handler.handle(traversal);
        assertEquals(404, traversal.getResponseCode());

        FakeExchange missing = new FakeExchange("GET", URI.create("http://127.0.0.1/missing.txt"));
        handler.handle(missing);
        assertEquals(404, missing.getResponseCode());
    }

    @Test
    void setsCssContentType() throws Exception {
        Files.writeString(tempDir.resolve("app.css"), "body{color:black;}", StandardCharsets.UTF_8);
        StaticFileHandler handler = new StaticFileHandler(tempDir, "index.html", 1234, null);

        FakeExchange ex = new FakeExchange("GET", URI.create("http://127.0.0.1/app.css"));
        handler.handle(ex);

        assertEquals(200, ex.getResponseCode());
        assertEquals("text/css; charset=utf-8", ex.getResponseHeaders().getFirst("Content-Type"));
    }

    @Test
    void redirectsToCanonicalLoopbackHostWhenConfigured() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        StaticFileHandler handler = new StaticFileHandler(
                tempDir,
                "index.html",
                1234,
                URI.create("http://127.0.0.1:1234/api/auth/callback")
        );

        FakeExchange ex = new FakeExchange("GET", URI.create("http://localhost/"));
        ex.getRequestHeaders().add("Host", "localhost:1234");
        handler.handle(ex);

        assertEquals(302, ex.getResponseCode());
        assertEquals("http://127.0.0.1:1234/", ex.getResponseHeaders().getFirst("Location"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private int responseCode;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        String responseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
