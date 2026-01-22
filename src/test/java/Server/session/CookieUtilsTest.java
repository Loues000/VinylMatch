package Server.session;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class CookieUtilsTest {

    @Test
    void setsLaxCookieAndSanitizesValue() {
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/"));
        exchange.getRequestHeaders().add("X-Forwarded-Proto", "https");

        CookieUtils.setCookie(exchange, "spotify_session", "abc.DEF;=<>", 10, true);

        String setCookie = exchange.getResponseHeaders().getFirst("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("spotify_session="));
        assertFalse(setCookie.contains(";=<>"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Secure"));
    }

    @Test
    void readsCookieByName() {
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/"));
        exchange.getRequestHeaders().add("Cookie", "a=1; spotify_session=xyz; b=2");
        assertEquals("xyz", CookieUtils.getCookie(exchange, "spotify_session"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
