package Server.routes;

import Server.session.DiscogsSessionStore;
import Server.session.SpotifySessionStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsRoutesTest {

    @Test
    void callbackHtmlEscapesUserFacingErrorMessage() throws Exception {
        DiscogsRoutes routes = new DiscogsRoutes(() -> null, new DiscogsSessionStore(), new SpotifySessionStore());
        Method method = DiscogsRoutes.class.getDeclaredMethod("sendOAuthCallbackHtml", HttpExchange.class, boolean.class, String.class);
        method.setAccessible(true);

        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/discogs/oauth/callback"));
        String message = "<script>alert(1)</script>";
        method.invoke(routes, exchange, false, message);

        String body = exchange.responseBodyAsString();
        assertEquals(200, exchange.getResponseCode());
        assertFalse(body.contains(message));
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(body.contains("data-callback-message=\"&lt;script&gt;alert(1)&lt;/script&gt;\""));
        assertTrue(body.contains("window.opener.postMessage(payload, window.location.origin);"));
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
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
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
